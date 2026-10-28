package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class ProgressController(
    private val domainStore: DomainStore,
) {
    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun createGoal(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        required(body, "goalType", "startDate", "endDate", "weeklyWorkoutTarget")
        if (domainStore.list(userId, "goal").any { it.payload["status"] == "ACTIVE" }) {
            throw ApiException(HttpStatus.CONFLICT, "ACTIVE_GOAL_EXISTS", "An active goal already exists")
        }
        val target = body.int("weeklyWorkoutTarget")
        if (target !in 1..14) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_GOAL", "weeklyWorkoutTarget must be between 1 and 14")
        }
        val goalId = UUID.randomUUID().toString()
        val week = weekStart(LocalDate.now()).toString()
        val payload = mutableMapOf<String, Any?>(
            "goalId" to goalId,
            "goalType" to body["goalType"],
            "startDate" to body["startDate"],
            "endDate" to body["endDate"],
            "weeklyWorkoutTarget" to target,
            "status" to "ACTIVE",
            "growth" to 0,
            "version" to 1,
            "createdAt" to nowIso(),
        )
        domainStore.save(userId, "goal", payload, goalId)
        domainStore.save(
            userId,
            "weekly_goal",
            mutableMapOf("goalId" to goalId, "week" to week, "targetCount" to target, "completedCount" to 0, "achieved" to false),
            week,
        )
        return mapOf(
            "goalId" to goalId,
            "status" to "ACTIVE",
            "growth" to 0,
            "weeklyGoal" to mapOf("weekStart" to week, "targetCount" to target, "completedCount" to 0),
        )
    }

    @GetMapping("/goals/active/progress")
    fun goalProgress(request: HttpServletRequest): Map<String, Any?> {
        val userId = userId(request)
        val goal = domainStore.list(userId, "goal").firstOrNull { it.payload["status"] == "ACTIVE" }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ACTIVE_GOAL_NOT_FOUND", "No active goal")
        val growth = growthValue(userId)
        return mapOf(
            "goalId" to goal.id,
            "growth" to growth,
            "previousGrowth" to (growth - 10).coerceAtLeast(0),
            "stage" to growthStage(growth),
            "factors" to mapOf("completedWorkouts" to growth / 10),
            "updatedAt" to nowIso(),
        )
    }

    @PostMapping("/goals/{goalId}/complete")
    @Transactional
    fun completeGoal(
        @PathVariable goalId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val goal = domainStore.get(userId, "goal", goalId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "GOAL_NOT_FOUND", "Goal not found")
        val payload = goal.payload
        required(body, "expectedVersion")
        if (payload["status"] != "COMPLETED" && body.int("expectedVersion") != payload.int("version", 1)) {
            throw ApiException(HttpStatus.CONFLICT, "GOAL_VERSION_CONFLICT", "Goal changed; reload its latest version")
        }
        if (payload["status"] != "COMPLETED" && growthValue(userId) < 100) {
            throw ApiException(HttpStatus.BAD_REQUEST, "GROWTH_NOT_REACHED", "Growth has not reached 100")
        }
        if (payload["status"] != "COMPLETED") {
            payload.putAll(mapOf(
                "status" to "COMPLETED",
                "completedAt" to nowIso(),
                "rewards" to listOf("PEAK_FORM"),
                "version" to payload.int("version", 1) + 1,
            ))
            domainStore.save(userId, "goal", payload, goalId)
        }
        return mapOf(
            "goalId" to goalId,
            "status" to "COMPLETED",
            "peakFormUnlocked" to true,
            "rewards" to (payload["rewards"] ?: listOf("PEAK_FORM")),
            "completedAt" to payload["completedAt"],
        )
    }

    @GetMapping("/goals/weekly/current")
    fun currentWeeklyGoal(
        request: HttpServletRequest,
        @RequestParam(required = false) timezone: String?,
    ): Map<String, Any?> {
        val userId = userId(request)
        val goal = domainStore.list(userId, "goal").firstOrNull { it.payload["status"] == "ACTIVE" }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "WEEKLY_GOAL_NOT_FOUND", "No active weekly goal")
        val week = weekStart(LocalDate.now())
        val count = domainStore.list(userId, "workout").count { workout ->
            workout.payload["status"] == "COMPLETED" && dateOf(workout.payload["startedAt"]) >= week
        }
        val target = goal.payload.int("weeklyWorkoutTarget")
        return mapOf(
            "weekStart" to week.toString(),
            "weekEnd" to week.plusDays(6).toString(),
            "targetCount" to target,
            "completedCount" to count,
            "achieved" to (count >= target),
        )
    }

    @GetMapping("/streaks/weekly")
    fun weeklyStreak(request: HttpServletRequest): Map<String, Any?> {
        val userId = userId(request)
        val goal = domainStore.list(userId, "goal").firstOrNull { it.payload["status"] == "ACTIVE" }
            ?: return mapOf("currentStreak" to 0, "longestStreak" to 0, "lastAchievedWeek" to null, "status" to "NOT_STARTED")
        val target = goal.payload.int("weeklyWorkoutTarget")
        val counts = domainStore.list(userId, "workout")
            .filter { it.payload["status"] == "COMPLETED" }
            .groupingBy { weekStart(dateOf(it.payload["startedAt"])) }
            .eachCount()
        val achieved = counts.filterValues { it >= target }.keys.sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        achieved.forEach { week ->
            run = if (previous != null && ChronoUnit.DAYS.between(previous, week) == 7L) run + 1 else 1
            longest = maxOf(longest, run)
            previous = week
        }
        var current = 0
        var expected = weekStart(LocalDate.now())
        val achievedWeeks = achieved.toSet()
        while (expected in achievedWeeks) {
            current += 1
            expected = expected.minusWeeks(1)
        }
        return mapOf(
            "currentStreak" to current,
            "longestStreak" to longest,
            "lastAchievedWeek" to achieved.lastOrNull()?.toString(),
            "status" to if (current > 0) "ACHIEVED" else "IN_PROGRESS",
        )
    }

    @GetMapping("/avatar")
    fun avatar(request: HttpServletRequest): Map<String, Any?> {
        val userId = userId(request)
        val growth = growthValue(userId)
        val xp = domainStore.list(userId, "reward").sumOf { it.payload.int("xp") }
        val profile = domainStore.get(userId, "profile", "me", forUpdate = false)
        val stage = growthStage(growth)
        return mapOf(
            "level" to xp / 1000 + 1,
            "growth" to growth,
            "stage" to stage,
            "modelKey" to stage.lowercase().replace(" ", "_"),
            "morphValue" to growth / 100.0,
            "equippedItems" to emptyList<Any>(),
            "weeklyGoal" to (profile?.payload?.int("weeklyWorkoutTarget") ?: 0),
        )
    }

    @GetMapping("/avatar/growth-stage")
    fun growthStageResponse(request: HttpServletRequest): Map<String, Any?> {
        val growth = growthValue(userId(request))
        val stage = growthStage(growth)
        val (minimum, maximum) = when {
            growth >= 100 -> 100 to 100
            growth >= 50 -> 50 to 99
            else -> 0 to 49
        }
        return mapOf(
            "growth" to growth,
            "stage" to stage,
            "minGrowth" to minimum,
            "maxGrowth" to maximum,
            "modelKey" to stage.lowercase().replace(" ", "_"),
            "morphValue" to growth / 100.0,
        )
    }

    @GetMapping("/avatar/items")
    fun avatarItems(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "all") status: String,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
    ): Map<String, Any?> {
        userId(request)
        if (size !in 1..100) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "size must be between 1 and 100")
        decodeCursor(cursor)
        return mapOf("items" to emptyList<Any>(), "nextCursor" to null)
    }

    @GetMapping("/users/me/level")
    fun levelInfo(request: HttpServletRequest): Map<String, Any?> {
        val xp = domainStore.list(userId(request), "reward").sumOf { it.payload.int("xp") }
        val current = xp % 1000
        return mapOf("level" to xp / 1000 + 1, "currentXp" to current, "nextLevelXp" to 1000, "progressPercent" to current / 10.0, "lastLevelUpAt" to null)
    }

    @GetMapping("/rewards/workouts/{workoutId}")
    fun workoutReward(@PathVariable workoutId: String, request: HttpServletRequest): Map<String, Any?> =
        domainStore.get(userId(request), "reward", workoutId, forUpdate = false)?.payload
            ?: throw ApiException(HttpStatus.NOT_FOUND, "REWARD_NOT_FOUND", "Reward not found")

    private fun growthValue(userId: String): Int =
        minOf(100, domainStore.list(userId, "workout").count { it.payload["status"] == "COMPLETED" } * 10)

    private fun growthStage(growth: Int): String = when {
        growth >= 100 -> "Peak Form"
        growth >= 50 -> "Intermediate"
        else -> "Beginner"
    }

    private fun weekStart(day: LocalDate): LocalDate = day.minusDays((day.dayOfWeek.value - 1).toLong())

    private fun dateOf(value: Any?): LocalDate = LocalDate.parse(value.toString().take(10))

    private fun nowIso(): String = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()

    private fun decodeCursor(cursor: String?): Int {
        if (cursor == null) return 0
        return try {
            java.util.Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "cursor is invalid")
        }
    }

    private fun userId(request: HttpServletRequest): String =
        request.getAttribute(JwtAuthenticationInterceptor.USER_ID_ATTRIBUTE) as? String
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bearer token is required")

    private fun required(body: Map<String, Any?>, vararg keys: String) {
        val missing = keys.filterNot(body::containsKey)
        if (missing.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Required fields: ${missing.joinToString(", ")}")
        }
    }

    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default
}
