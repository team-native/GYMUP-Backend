package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.ceil

@RestController
@RequestMapping("/api/v1/workouts/{workoutId}/rests")
class RestTimerController(
    private val domainStore: DomainStore,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun startRest(
        @PathVariable workoutId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val workout = domainStore.get(userId, "workout", workoutId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "WORKOUT_OR_SET_NOT_FOUND", "Workout not found")
        if (workout.payload["status"] != "IN_PROGRESS") {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_WORKOUT_STATE", "Workout is not active")
        }
        if (activeRest(userId, workoutId) != null) {
            throw ApiException(HttpStatus.CONFLICT, "REST_ALREADY_ACTIVE", "An active rest timer already exists")
        }
        val setId = body["setId"]?.toString()
        if (workout.payload.objectList("sets").none { it["setId"] == setId }) {
            throw ApiException(HttpStatus.NOT_FOUND, "WORKOUT_OR_SET_NOT_FOUND", "Set not found")
        }
        val duration = body.int("durationSeconds", 90)
        if (duration !in 1..3600) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_DURATION", "durationSeconds must be between 1 and 3600")
        }
        val startedAt = OffsetDateTime.now(ZoneOffset.UTC)
        val payload = mutableMapOf<String, Any?>(
            "restId" to UUID.randomUUID().toString(),
            "workoutId" to workoutId,
            "setId" to setId,
            "status" to "ACTIVE",
            "startedAt" to startedAt.toString(),
            "endsAt" to startedAt.plusSeconds(duration.toLong()).toString(),
            "durationSeconds" to duration,
        )
        domainStore.save(userId, "rest", payload, payload["restId"].toString())
        return payload + mapOf("nextSet" to null)
    }

    @GetMapping("/current")
    fun currentRest(@PathVariable workoutId: String, request: HttpServletRequest): Map<String, Any?> {
        val rest = activeRest(userId(request), workoutId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ACTIVE_REST_NOT_FOUND", "No active rest timer")
        val payload = rest.payload
        val endsAt = dateTime(payload["endsAt"])
        val remaining = remainingSeconds(endsAt)
        return mapOf(
            "restId" to rest.id,
            "status" to payload["status"],
            "startedAt" to payload["startedAt"],
            "endsAt" to payload["endsAt"],
            "remainingSeconds" to remaining,
            "notificationAt" to payload["endsAt"],
        )
    }

    @PatchMapping("/current/extend")
    @Transactional
    fun extendRest(
        @PathVariable workoutId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val rest = activeRest(userId, workoutId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ACTIVE_REST_NOT_FOUND", "No active rest timer")
        if (body.int("seconds", 15) != 15) {
            throw ApiException(HttpStatus.BAD_REQUEST, "MAX_REST_EXCEEDED", "Rest can be extended by 15 seconds")
        }
        val payload = rest.payload
        val endsAt = dateTime(payload["endsAt"]).plusSeconds(15)
        if (Duration.between(dateTime(payload["startedAt"]), endsAt) > Duration.ofMinutes(30)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "MAX_REST_EXCEEDED", "Maximum rest duration exceeded")
        }
        payload["endsAt"] = endsAt.toString()
        domainStore.save(userId, "rest", payload, rest.id)
        return mapOf(
            "restId" to rest.id,
            "endsAt" to payload["endsAt"],
            "remainingSeconds" to remainingSeconds(endsAt),
        )
    }

    @PostMapping("/current/skip")
    @Transactional
    fun skipRest(@PathVariable workoutId: String, request: HttpServletRequest): Map<String, Any?> {
        val userId = userId(request)
        val rest = activeRest(userId, workoutId)
        if (rest == null) {
            val skipped = domainStore.list(userId, "rest").firstOrNull {
                it.payload["workoutId"] == workoutId && it.payload["status"] == "SKIPPED"
            }
            if (skipped != null) return mapOf("restId" to skipped.id, "status" to "SKIPPED", "nextSet" to null)
            throw ApiException(HttpStatus.NOT_FOUND, "ACTIVE_REST_NOT_FOUND", "No active rest timer")
        }
        val payload = rest.payload.toMutableMap().apply {
            put("status", "SKIPPED")
            put("endedAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
        }
        domainStore.save(userId, "rest", payload, rest.id)
        return mapOf("restId" to rest.id, "status" to "SKIPPED", "nextSet" to null)
    }

    private fun activeRest(userId: String, workoutId: String) =
        domainStore.list(userId, "rest").firstOrNull {
            it.payload["workoutId"] == workoutId && it.payload["status"] == "ACTIVE"
        }

    private fun userId(request: HttpServletRequest): String =
        request.getAttribute(JwtAuthenticationInterceptor.USER_ID_ATTRIBUTE) as? String
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bearer token is required")

    private fun dateTime(value: Any?): OffsetDateTime = OffsetDateTime.parse(value.toString())

    private fun remainingSeconds(endsAt: OffsetDateTime): Int {
        val remainingMillis = Duration.between(OffsetDateTime.now(ZoneOffset.UTC), endsAt).toMillis()
        return ceil(remainingMillis.coerceAtLeast(0) / 1000.0).toInt()
    }

    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
}
