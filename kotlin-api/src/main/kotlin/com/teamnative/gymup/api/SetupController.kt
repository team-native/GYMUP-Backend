package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainRecord
import com.teamnative.gymup.db.DomainStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
class SetupController(
    private val domainStore: DomainStore,
) {
    @PutMapping("/users/me/onboarding")
    @Transactional
    fun onboarding(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        required(body, "experienceLevel", "goalType", "weeklyWorkoutTarget")
        val current = domainStore.get(userId, "profile", "me")
        if (current?.payload?.get("profileCompleted") == true) {
            throw ApiException(HttpStatus.CONFLICT, "ALREADY_COMPLETED", "Onboarding has already been completed")
        }

        val target = body.int("weeklyWorkoutTarget")
        if (target !in 1..14) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "weeklyWorkoutTarget must be between 1 and 14")
        }

        val payload = mutableMapOf<String, Any?>(
            "profileCompleted" to true,
            "experienceLevel" to body["experienceLevel"],
            "goalType" to body["goalType"],
            "weeklyWorkoutTarget" to target,
        )
        domainStore.save(userId, "profile", payload, "me")
        return mapOf("userId" to userId) + payload
    }

    @PatchMapping("/users/me/settings/rest-time")
    @Transactional
    fun updateRestTime(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        required(body, "defaultRestSeconds")
        val seconds = body.int("defaultRestSeconds")
        if (seconds !in 1..900) {
            throw ApiException(HttpStatus.BAD_REQUEST, "OUT_OF_RANGE", "defaultRestSeconds must be between 1 and 900")
        }

        val current = domainStore.get(userId, "settings", "me")
        val payload = current?.payload?.toMutableMap() ?: mutableMapOf()
        val updatedAt = nowIso()
        payload.putAll(mapOf("defaultRestSeconds" to seconds, "updatedAt" to updatedAt))
        domainStore.save(userId, "settings", payload, "me")
        return mapOf("defaultRestSeconds" to seconds, "updatedAt" to updatedAt)
    }

    @PostMapping("/routines")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun createRoutine(
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        required(body, "name", "exercises")
        val exercises = body.objectList("exercises")
        if (exercises.isEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A routine must contain at least one exercise")
        }
        exercises.forEach { exercise ->
            required(exercise, "exerciseId", "sets", "reps", "weight", "restSeconds")
            if (exercise.int("sets") < 1 || exercise.int("reps") < 1 || exercise.double("weight") < 0) {
                throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid set, rep, or weight value")
            }
        }

        val routineId = UUID.randomUUID().toString()
        val orderedExercises = exercises.mapIndexed { index, exercise ->
            exercise.toMutableMap().apply { put("order", exercise.int("order", index)) }
        }
        val payload = mutableMapOf<String, Any?>(
            "routineId" to routineId,
            "name" to body["name"].toString().trim(),
            "exercises" to orderedExercises,
            "createdAt" to nowIso(),
        )
        domainStore.save(userId, "routine", payload, routineId)
        return payload
    }

    @PatchMapping("/routines/{routineId}/exercises")
    @Transactional
    fun editRoutine(
        @PathVariable routineId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val routine = ownedRoutine(userId, routineId)
        val removeIds = body.stringList("removeExerciseIds").toSet()
        val current = routine.payload.objectList("exercises")
        val updates = body.objectList("update").associateBy { it["exerciseId"]?.toString() }
        val exercises = current
            .filterNot { it["exerciseId"]?.toString() in removeIds }
            .map { exercise -> exercise + (updates[exercise["exerciseId"]?.toString()] ?: emptyMap()) } + body.objectList("add")
        if (exercises.isEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ROUTINE", "A routine cannot be empty")
        }

        val payload = routine.payload.toMutableMap().apply {
            put("exercises", exercises)
            put("updatedAt", nowIso())
        }
        domainStore.save(userId, "routine", payload, routineId)
        return payload
    }

    @PutMapping("/routines/{routineId}/exercises/order")
    @Transactional
    fun reorderRoutine(
        @PathVariable routineId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val routine = ownedRoutine(userId, routineId)
        val ids = body.stringList("exerciseIds")
        val existing = routine.payload.objectList("exercises").associateBy { it["exerciseId"].toString() }
        if (ids.size != existing.size || ids.toSet() != existing.keys) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_ORDER", "exerciseIds must contain every exercise exactly once")
        }

        val ordered = ids.mapIndexed { index, id -> existing.getValue(id) + mapOf("order" to index) }
        val updatedAt = nowIso()
        val payload = routine.payload.toMutableMap().apply {
            put("exercises", ordered)
            put("updatedAt", updatedAt)
        }
        domainStore.save(userId, "routine", payload, routineId)
        return mapOf("routineId" to routineId, "orderedExercises" to ordered, "updatedAt" to updatedAt)
    }

    @PutMapping("/routines/{routineId}/playlist")
    @Transactional
    fun setPlaylist(
        @PathVariable routineId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val routine = ownedRoutine(userId, routineId)
        required(body, "provider", "playlistId", "playlistName", "externalUrl")
        if (body["provider"].toString().lowercase() !in setOf("spotify", "apple_music", "apple music")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PROVIDER", "provider must be Spotify or Apple Music")
        }

        val updatedAt = nowIso()
        val payload = routine.payload.toMutableMap().apply {
            put("playlist", body)
            put("updatedAt", updatedAt)
        }
        domainStore.save(userId, "routine", payload, routineId)
        return mapOf("routineId" to routineId, "playlist" to body, "updatedAt" to updatedAt)
    }

    private fun ownedRoutine(userId: String, routineId: String): DomainRecord =
        domainStore.get(userId, "routine", routineId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "ROUTINE_NOT_FOUND", "routine not found")

    private fun userId(request: HttpServletRequest): String =
        request.getAttribute(JwtAuthenticationInterceptor.USER_ID_ATTRIBUTE) as? String
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bearer token is required")

    private fun required(body: Map<String, Any?>, vararg keys: String) {
        val missing = keys.filterNot(body::containsKey)
        if (missing.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Required fields: ${missing.joinToString(", ")}")
        }
    }

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun Map<String, Any?>.int(key: String, default: Int = 0): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: default

    private fun Map<String, Any?>.double(key: String): Double =
        (this[key] as? Number)?.toDouble() ?: this[key]?.toString()?.toDoubleOrNull() ?: Double.NaN

    private fun Map<String, Any?>.objectList(key: String): List<Map<String, Any?>> =
        (this[key] as? List<*>)?.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            (item as? Map<String, Any?>)?.toMap()
        } ?: emptyList()

    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
}
