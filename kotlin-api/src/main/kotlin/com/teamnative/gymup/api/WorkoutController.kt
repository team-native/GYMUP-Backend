package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainRecord
import com.teamnative.gymup.db.DomainStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.math.max

@RestController
@RequestMapping("/api/v1")
class WorkoutController(
    private val domainStore: DomainStore,
) {
    @PostMapping("/workouts")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun startWorkout(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        required(body, "routineId")
        val routineId = body["routineId"].toString()
        val routine = domainStore.get(userId, "routine", routineId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ROUTINE_NOT_FOUND", "Routine not found")
        if (domainStore.list(userId, "workout").any { it.payload["status"] == "IN_PROGRESS" }) {
            throw ApiException(HttpStatus.CONFLICT, "ACTIVE_WORKOUT_EXISTS", "An active workout already exists")
        }

        val snapshot = routine.payload.toMap()
        val workoutId = UUID.randomUUID().toString()
        val startedAt = body["startedAt"]?.toString() ?: nowIso()
        val payload = mutableMapOf<String, Any?>(
            "workoutId" to workoutId,
            "routineId" to routine.id,
            "routine" to snapshot,
            "routineName" to snapshot["name"],
            "status" to "IN_PROGRESS",
            "startedAt" to startedAt,
            "timezone" to (body["timezone"]?.toString() ?: "UTC"),
            "currentExerciseIndex" to 0,
            "sets" to emptyList<Map<String, Any?>>(),
            "prs" to emptyList<Map<String, Any?>>(),
        )
        domainStore.save(userId, "workout", payload, workoutId)
        return mapOf(
            "workoutId" to workoutId,
            "status" to "IN_PROGRESS",
            "routineSnapshot" to snapshot,
            "currentExercise" to snapshot.objectList("exercises").firstOrNull(),
            "startedAt" to startedAt,
            "version" to payload["version"],
        )
    }

    @PostMapping("/workouts/{workoutId}/sets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun recordSet(
        @PathVariable workoutId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val workout = ownedWorkout(userId, workoutId)
        val payload = workout.payload
        if (payload["status"] != "IN_PROGRESS") {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Workout is not active")
        }
        required(body, "exerciseId", "setIndex", "weight", "reps")
        val exercises = payload.objectList("routine", "exercises")
        val index = payload.int("currentExerciseIndex")
        if (index !in exercises.indices) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SET", "There is no current exercise")
        }
        val planned = exercises[index]
        val exerciseId = body["exerciseId"].toString()
        if (exerciseId != planned["exerciseId"].toString()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SET", "exerciseId is not the current exercise")
        }
        val setIndex = body.int("setIndex")
        if (setIndex !in 1..planned.int("sets")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SET", "setIndex is outside the planned range")
        }
        val sets = payload.objectList("sets")
        if (sets.any { it["exerciseId"].toString() == exerciseId && it.int("setIndex") == setIndex }) {
            throw ApiException(HttpStatus.CONFLICT, "DUPLICATE_SET", "This set has already been recorded")
        }
        val weight = body.double("weight")
        val reps = body.int("reps")
        if (weight < 0 || reps < 1) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_SET", "weight must be non-negative and reps must be positive")
        }

        val previous = previousRecord(userId, exerciseId)
        val isPr = previous == null || weight * reps > previous.double("weight") * previous.int("reps")
        val item = mapOf(
            "setId" to UUID.randomUUID().toString(),
            "exerciseId" to exerciseId,
            "exerciseName" to (planned["name"] ?: planned["exerciseName"] ?: "Exercise"),
            "setIndex" to setIndex,
            "weight" to weight,
            "reps" to reps,
            "completedAt" to (body["completedAt"]?.toString() ?: nowIso()),
            "pr" to isPr,
        )
        val updatedSets = sets + item
        val prs = payload.objectList("prs") + if (isPr) {
            listOf(mapOf("exerciseId" to exerciseId, "weight" to weight, "reps" to reps))
        } else {
            emptyList()
        }
        payload["sets"] = updatedSets
        payload["prs"] = prs
        domainStore.save(userId, "workout", payload, workoutId)

        val count = updatedSets.count { it["exerciseId"].toString() == exerciseId }
        val action = if (count < planned.int("sets")) "REST" else "NEXT_EXERCISE"
        return item + mapOf(
            "workoutId" to workoutId,
            "nextAction" to action,
            "rest" to if (action == "REST") mapOf("durationSeconds" to planned.int("restSeconds", 90)) else null,
            "version" to payload["version"],
        )
    }

    @GetMapping("/exercises/{exerciseId}/previous-record")
    fun previousRecord(
        @PathVariable exerciseId: String,
        request: HttpServletRequest,
        @RequestParam(required = false) beforeWorkoutId: String?,
    ): Map<String, Any?> {
        val record = previousRecord(userId(request), exerciseId)
            ?: return mapOf("exerciseId" to exerciseId, "hasPreviousRecord" to false)
        return mapOf(
            "exerciseId" to exerciseId,
            "hasPreviousRecord" to true,
            "weight" to record["weight"],
            "reps" to record["reps"],
            "setIndex" to record["setIndex"],
            "performedAt" to record["completedAt"],
        )
    }

    @GetMapping("/workouts/{workoutId}/next-set")
    fun nextSet(@PathVariable workoutId: String, request: HttpServletRequest): Map<String, Any?> {
        val workout = domainStore.get(userId(request), "workout", workoutId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "WORKOUT_OR_NEXT_SET_NOT_FOUND", "Workout not found")
        val payload = workout.payload
        val exercises = payload.objectList("routine", "exercises")
        val index = payload.int("currentExerciseIndex")
        if (index !in exercises.indices) {
            throw ApiException(HttpStatus.NOT_FOUND, "WORKOUT_OR_NEXT_SET_NOT_FOUND", "No next set")
        }
        val exercise = exercises[index]
        val exerciseId = exercise["exerciseId"].toString()
        val completed = payload.objectList("sets").count { it["exerciseId"].toString() == exerciseId }
        return mapOf(
            "exerciseId" to exercise["exerciseId"],
            "exerciseName" to (exercise["name"] ?: exercise["exerciseName"] ?: "Exercise"),
            "setIndex" to completed + 1,
            "weight" to (exercise["weight"] ?: 0),
            "reps" to (exercise["reps"] ?: 0),
            "restEndsAt" to null,
        )
    }

    @PatchMapping("/workouts/{workoutId}/current-exercise")
    @Transactional
    fun advanceExercise(
        @PathVariable workoutId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val workout = ownedWorkout(userId, workoutId)
        val payload = workout.payload
        val exercises = payload.objectList("routine", "exercises")
        val index = payload.int("currentExerciseIndex")
        if (index !in exercises.indices) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Workout has no current exercise")
        }
        val current = exercises[index]
        if (body["completedExerciseId"] != current["exerciseId"]) {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "completedExerciseId does not match current exercise")
        }
        val completed = payload.objectList("sets").count { it["exerciseId"].toString() == current["exerciseId"].toString() }
        if (completed < current.int("sets")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "SETS_INCOMPLETE", "Complete all planned sets before advancing")
        }

        payload["currentExerciseIndex"] = index + 1
        val next = exercises.getOrNull(index + 1)
        domainStore.save(userId, "workout", payload, workoutId)
        return mapOf(
            "workoutId" to workoutId,
            "currentExercise" to next,
            "nextExercise" to next,
            "status" to if (next == null) "READY_TO_COMPLETE" else "IN_PROGRESS",
            "version" to payload["version"],
        )
    }

    @PostMapping("/workouts/{workoutId}/complete")
    @Transactional
    fun completeWorkout(
        @PathVariable workoutId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val workout = ownedWorkout(userId, workoutId)
        val payload = workout.payload
        if (payload["status"] == "COMPLETED") return completion(payload)
        if (!body.containsKey("expectedVersion")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "expectedVersion is required")
        }
        if (body.int("expectedVersion") != payload.int("version", 1)) {
            throw ApiException(HttpStatus.CONFLICT, "WORKOUT_VERSION_CONFLICT", "Workout changed; reload its latest version")
        }
        val exercises = payload.objectList("routine", "exercises")
        val sets = payload.objectList("sets")
        if (exercises.any { exercise -> sets.count { it["exerciseId"].toString() == exercise["exerciseId"].toString() } < exercise.int("sets") }) {
            throw ApiException(HttpStatus.BAD_REQUEST, "WORKOUT_INCOMPLETE", "Complete all planned exercises and sets")
        }

        val endedAt = body["endedAt"]?.toString()?.let(::parseDateTime) ?: OffsetDateTime.now(ZoneOffset.UTC)
        val startedAt = parseDateTime(payload["startedAt"].toString())
        val xp = sets.size * 10 + 50
        payload.putAll(
            mapOf(
                "status" to "COMPLETED",
                "endedAt" to endedAt.toString(),
                "duration" to max(0, Duration.between(startedAt, endedAt).seconds).toInt(),
                "totalSets" to sets.size,
                "volume" to volume(payload),
                "xp" to xp,
                "growthDelta" to 10,
                "completedAt" to endedAt.toString(),
            ),
        )
        domainStore.save(userId, "workout", payload, workoutId)
        domainStore.save(
            userId,
            "reward",
            mutableMapOf("workoutId" to workoutId, "xp" to xp, "growthDelta" to 10, "bonuses" to emptyList<Any>(), "grantedAt" to endedAt.toString()),
            workoutId,
        )
        return completion(payload)
    }

    @GetMapping("/workouts/{workoutId}/summary")
    fun workoutSummary(@PathVariable workoutId: String, request: HttpServletRequest): Map<String, Any?> {
        val userId = userId(request)
        val workout = domainStore.get(userId, "workout", workoutId)
        if (workout == null || workout.payload["status"] != "COMPLETED") {
            throw ApiException(HttpStatus.NOT_FOUND, "SUMMARY_NOT_FOUND", "Completed workout summary not found")
        }
        val payload = workout.payload
        return mapOf(
            "duration" to payload["duration"],
            "totalSets" to payload["totalSets"],
            "volume" to payload["volume"],
            "prs" to payload["prs"],
            "growthDelta" to payload["growthDelta"],
            "xp" to payload["xp"],
            "levelUp" to false,
            "avatarGrowth" to growthValue(userId),
            "progressShotAvailable" to true,
        )
    }

    @GetMapping("/workouts/{workoutId}")
    fun workoutDetail(@PathVariable workoutId: String, request: HttpServletRequest): Map<String, Any?> {
        val workout = ownedWorkout(userId(request), workoutId)
        val payload = workout.payload
        val sets = payload.objectList("sets")
        val exercises = payload.objectList("routine", "exercises").map { exercise ->
            val exerciseId = exercise["exerciseId"].toString()
            mapOf(
                "exerciseId" to exercise["exerciseId"],
                "name" to (exercise["name"] ?: exercise["exerciseName"] ?: "Exercise"),
                "sets" to sets.filter { it["exerciseId"].toString() == exerciseId },
            )
        }
        return mapOf(
            "workoutId" to workoutId,
            "routine" to payload["routineName"],
            "duration" to (payload["duration"] ?: 0),
            "totalSets" to sets.size,
            "volume" to volume(payload),
            "exercises" to exercises,
        )
    }

    @GetMapping("/workouts")
    fun workoutHistory(
        request: HttpServletRequest,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) routineId: String?,
    ): Map<String, Any?> {
        if (size !in 1..100) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "size must be between 1 and 100")
        val docs = domainStore.list(userId(request), "workout")
            .filter { it.payload["status"] == "COMPLETED" }
            .filter { routineId == null || it.payload["routineId"] == routineId }
            .filter { from == null || dateOf(it.payload["startedAt"]) >= from }
            .filter { to == null || dateOf(it.payload["startedAt"]) <= to }
        val offset = decodeCursor(cursor)
        val page = docs.drop(offset).take(size)
        return mapOf(
            "items" to page.map { doc ->
                mapOf(
                    "workoutId" to doc.id,
                    "date" to doc.payload["startedAt"],
                    "routineName" to doc.payload["routineName"],
                    "duration" to doc.payload["duration"],
                    "totalSets" to doc.payload["totalSets"],
                    "volume" to doc.payload["volume"],
                )
            },
            "nextCursor" to encodeCursor(offset, size, docs.size),
        )
    }

    @GetMapping("/statistics/exercises/{exerciseId}")
    fun exerciseStatistics(
        @PathVariable exerciseId: String,
        request: HttpServletRequest,
        @RequestParam(defaultValue = "volume") metric: String,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "week") interval: String,
    ): Map<String, Any?> {
        val points = mutableMapOf<String, Double>()
        domainStore.list(userId(request), "workout")
            .filter { it.payload["status"] == "COMPLETED" }
            .forEach { workout ->
                val day = dateOf(workout.payload["startedAt"])
                if ((from != null && day < from) || (to != null && day > to)) return@forEach
                val sets = workout.payload.objectList("sets").filter { it["exerciseId"].toString() == exerciseId }
                if (sets.isEmpty()) return@forEach
                val key = if (interval == "day") day.toString() else day.minusDays((day.dayOfWeek.value - 1).toLong()).toString()
                val value = when (metric) {
                    "volume" -> sets.sumOf { it.double("weight") * it.int("reps") }
                    "weight", "estimated1rm" -> sets.maxOf { it.double("weight") }
                    else -> sets.size.toDouble()
                }
                points[key] = (points[key] ?: 0.0) + value
            }
        val series = points.toSortedMap().map { (date, value) -> mapOf("date" to date, "value" to value) }
        val values = series.map { it["value"] as Double }
        return mapOf(
            "exerciseId" to exerciseId,
            "metric" to metric,
            "points" to series,
            "summary" to mapOf("latest" to values.lastOrNull(), "best" to values.maxOrNull()),
        )
    }

    fun growthValue(userId: String): Int =
        minOf(100, domainStore.list(userId, "workout").count { it.payload["status"] == "COMPLETED" } * 10)

    private fun previousRecord(userId: String, exerciseId: String): Map<String, Any?>? =
        domainStore.list(userId, "workout")
            .asSequence()
            .filter { it.payload["status"] == "COMPLETED" }
            .flatMap { it.payload.objectList("sets").asSequence() }
            .filter { it["exerciseId"].toString() == exerciseId }
            .maxByOrNull { it.double("weight") * it.int("reps") }

    private fun ownedWorkout(userId: String, workoutId: String): DomainRecord =
        domainStore.get(userId, "workout", workoutId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "WORKOUT_NOT_FOUND", "Workout not found")

    private fun completion(payload: Map<String, Any?>): Map<String, Any?> = mapOf(
        "workoutId" to payload["workoutId"],
        "status" to "COMPLETED",
        "duration" to (payload["duration"] ?: 0),
        "totalSets" to (payload["totalSets"] ?: payload.objectList("sets").size),
        "volume" to (payload["volume"] ?: volume(payload)),
        "prs" to (payload["prs"] ?: emptyList<Any>()),
        "xp" to (payload["xp"] ?: 0),
        "growthDelta" to (payload["growthDelta"] ?: 0),
        "version" to (payload["version"] ?: 1),
    )

    private fun volume(payload: Map<String, Any?>): Double =
        payload.objectList("sets").sumOf { it.double("weight") * it.int("reps") }

    private fun parseDateTime(value: String): OffsetDateTime = OffsetDateTime.parse(value)

    private fun dateOf(value: Any?): LocalDate = LocalDate.parse(value.toString().take(10))

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun decodeCursor(cursor: String?): Int {
        if (cursor == null) return 0
        return try {
            max(0, String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8).toInt())
        } catch (_: Exception) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "cursor is invalid")
        }
    }

    private fun encodeCursor(offset: Int, size: Int, total: Int): String? =
        if (offset + size < total) Base64.getUrlEncoder().withoutPadding()
            .encodeToString((offset + size).toString().toByteArray(Charsets.UTF_8)) else null

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

    private fun Map<String, Any?>.double(key: String): Double =
        (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull() ?: 0.0

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectList(vararg path: String): List<Map<String, Any?>> {
        val value = path.fold<String, Any?>(this) { current, key -> (current as? Map<*, *>)?.get(key) }
        return (value as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    }
}
