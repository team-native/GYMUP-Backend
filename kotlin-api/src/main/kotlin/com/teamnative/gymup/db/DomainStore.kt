package com.teamnative.gymup.db

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class DomainRecord(
    val id: String,
    val ownerId: String,
    val payload: MutableMap<String, Any?>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Repository
class DomainStore(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun get(ownerId: String, kind: String, id: String, forUpdate: Boolean = true): DomainRecord? {
        val table = TABLES[kind] ?: return legacy(ownerId, kind, id, forUpdate)
        val suffix = if (forUpdate) " FOR UPDATE" else ""
        val row = try {
            jdbcTemplate.queryForMap(
                "SELECT * FROM $table WHERE owner_id = ? AND id = ?$suffix",
                ownerId,
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }

        if (row == null) {
            val oldRecord = legacy(ownerId, kind, id, forUpdate) ?: return null
            if (!forUpdate) return oldRecord
            val parent = when (kind) {
                "workout" -> "routine" to oldRecord.payload["routineId"]
                "rest", "reward", "photo" -> "workout" to oldRecord.payload["workoutId"]
                "photo_job" -> "photo" to oldRecord.payload["photoId"]
                else -> null
            }
            if (parent != null && parent.second != null && get(ownerId, parent.first, parent.second.toString()) == null) {
                return oldRecord
            }
            return save(ownerId, kind, oldRecord.payload, id)
        }
        return serialize(kind, row)
    }

    @Transactional
    fun list(ownerId: String, kind: String): List<DomainRecord> {
        val table = TABLES[kind]
        val normalized = if (table == null) {
            emptyList()
        } else {
            jdbcTemplate.queryForList(
                "SELECT * FROM $table WHERE owner_id = ? ORDER BY created_at DESC",
                ownerId,
            ).map { serialize(kind, it) }
        }

        val legacyRows = jdbcTemplate.queryForList(
            "SELECT id, payload::text AS payload, created_at, updated_at FROM documents " +
                "WHERE owner_id = ? AND kind = ? ORDER BY created_at DESC",
            ownerId,
            kind,
        ).map { row ->
            val id = row.string("id")
            val createdAt = row.instant("created_at")
            DomainRecord(id, ownerId, jsonMap(row.string("payload")), createdAt, row.instant("updated_at"))
        }

        val existingIds = normalized.mapTo(mutableSetOf()) { it.id }
        return (normalized + legacyRows.filterNot { it.id in existingIds }).sortedByDescending { it.createdAt }
    }

    @Transactional(readOnly = true)
    fun getPhotoById(id: String): DomainRecord? {
        val ownerId = try {
            jdbcTemplate.queryForObject(
                "SELECT owner_id FROM progress_photos WHERE id = ? LIMIT 1",
                String::class.java,
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            null
        }
        return ownerId?.let { get(it, "photo", id, forUpdate = false) }
    }

    @Transactional
    fun save(ownerId: String, kind: String, payload: MutableMap<String, Any?>, requestedId: String? = null): DomainRecord {
        val id = requestedId ?: payloadId(kind, payload) ?: UUID.randomUUID().toString()
        val table = TABLES[kind] ?: throw IllegalArgumentException("Unsupported document kind: $kind")
        when (kind) {
            "profile" -> saveSimple(
                table, ownerId, id, payload,
                listOf("profile_completed", "experience_level", "goal_type", "weekly_workout_target"),
                listOf(payload["profileCompleted"] ?: false, payload["experienceLevel"], payload["goalType"], payload["weeklyWorkoutTarget"]),
                setOf("profileCompleted", "experienceLevel", "goalType", "weeklyWorkoutTarget", "userId"),
            )
            "settings" -> saveSimple(
                table, ownerId, id, payload,
                listOf("default_rest_seconds"),
                listOf(payload["defaultRestSeconds"]),
                setOf("defaultRestSeconds", "updatedAt"),
            )
            "routine" -> saveRoutine(ownerId, id, payload)
            "workout" -> saveWorkout(ownerId, id, payload)
            "goal" -> saveSimple(
                table, ownerId, id, payload,
                listOf("goal_type", "start_date", "end_date", "weekly_workout_target", "status", "growth"),
                listOf(payload["goalType"], payload["startDate"], payload["endDate"], payload["weeklyWorkoutTarget"], payload["status"], payload["growth"] ?: 0),
                setOf("goalId", "goalType", "startDate", "endDate", "weeklyWorkoutTarget", "status", "growth"),
                casts = mapOf("start_date" to "date", "end_date" to "date"),
            )
            "weekly_goal" -> saveSimple(
                table, ownerId, id, payload,
                listOf("week_start", "target_count", "completed_count", "achieved", "goal_id"),
                listOf(payload["week"] ?: id, payload["targetCount"] ?: 0, payload["completedCount"] ?: 0, payload["achieved"] ?: false, payload["goalId"]),
                setOf("goalId", "week", "targetCount", "completedCount", "achieved"),
                casts = mapOf("week_start" to "date"),
            )
            "reward" -> saveSimple(
                table, ownerId, id, payload,
                listOf("workout_id", "xp", "growth_delta", "granted_at"),
                listOf(payload["workoutId"], payload["xp"] ?: 0, payload["growthDelta"] ?: 0, payload["grantedAt"]),
                setOf("workoutId", "xp", "growthDelta", "grantedAt"),
                casts = mapOf("granted_at" to "timestamptz"),
            )
            "rest" -> saveSimple(
                table, ownerId, id, payload,
                listOf("workout_id", "set_id", "status", "started_at", "ends_at", "duration_seconds"),
                listOf(payload["workoutId"], payload["setId"], payload["status"], payload["startedAt"], payload["endsAt"], payload["durationSeconds"]),
                setOf("restId", "workoutId", "setId", "status", "startedAt", "endsAt", "durationSeconds"),
                casts = mapOf("started_at" to "timestamptz", "ends_at" to "timestamptz"),
            )
            "photo" -> saveSimple(
                table, ownerId, id, payload,
                listOf("workout_id", "file_name", "content_type", "size", "status", "storage_path", "edited_path", "captured_at", "expires_at"),
                listOf(payload["workoutId"], payload["fileName"], payload["contentType"], payload["size"], payload["status"], payload["storagePath"], payload["editedPath"], payload["capturedAt"], payload["expiresAt"]),
                setOf("photoId", "workoutId", "fileName", "contentType", "size", "status", "storagePath", "editedPath", "capturedAt", "expiresAt"),
                casts = mapOf("captured_at" to "timestamptz", "expires_at" to "timestamptz"),
            )
            "photo_job" -> savePhotoJob(ownerId, id, payload)
            "avatar_item" -> saveSimple(
                table, ownerId, id, payload,
                listOf("item_type", "unlocked"),
                listOf(payload["type"], payload["unlocked"] ?: false),
                setOf("itemId", "type", "unlocked"),
            )
        }

        jdbcTemplate.update(
            "DELETE FROM documents WHERE owner_id = ? AND kind = ? AND id = ?",
            ownerId,
            kind,
            id,
        )
        val saved = get(ownerId, kind, id, forUpdate = false)
            ?: error("Unable to read saved $kind record $id")
        if (kind == "workout") payload["version"] = saved.payload["version"]
        return saved
    }

    private fun saveRoutine(ownerId: String, id: String, payload: MutableMap<String, Any?>) {
        val known = setOf("routineId", "name", "exercises", "playlist", "createdAt", "updatedAt")
        val extras = payload.filterKeys { it !in known }
        jdbcTemplate.update(
            """INSERT INTO routines (owner_id, id, payload, name, playlist)
               VALUES (?, ?, ?::jsonb, ?, ?::jsonb)
               ON CONFLICT (owner_id, id) DO UPDATE SET payload = EXCLUDED.payload,
                 name = EXCLUDED.name, playlist = EXCLUDED.playlist, updated_at = now()""".trimIndent(),
            ownerId, id, json(extras), payload["name"] ?: "Routine", payload["playlist"]?.let(::json),
        )
        jdbcTemplate.update("DELETE FROM routine_exercises WHERE owner_id = ? AND routine_id = ?", ownerId, id)
        exercises(payload).forEachIndexed { index, exercise ->
            val exerciseId = exercise.requiredString("exerciseId")
            val exerciseKnown = setOf("exerciseId", "sets", "reps", "weight", "restSeconds", "order", "name")
            jdbcTemplate.update(
                """INSERT INTO routine_exercises
                   (owner_id, routine_id, exercise_id, order_index, name, set_count, reps, weight, rest_seconds, extra)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""".trimIndent(),
                ownerId, id, exerciseId, exercise.number("order", index.toDouble()).toInt(), exercise["name"],
                exercise.number("sets").toInt(), exercise.number("reps").toInt(), exercise.number("weight"),
                exercise.number("restSeconds", 90.0).toInt(), json(exercise.filterKeys { it !in exerciseKnown }),
            )
        }
    }

    private fun saveWorkout(ownerId: String, id: String, payload: MutableMap<String, Any?>) {
        val known = setOf("workoutId", "routineId", "routineName", "status", "startedAt", "endedAt", "timezone", "currentExerciseIndex", "duration", "totalSets", "volume", "xp", "growthDelta", "sets", "prs", "version", "completedAt")
        val extras = payload.filterKeys { it !in known }
        jdbcTemplate.update(
            """INSERT INTO workouts
               (owner_id, id, payload, routine_id, routine_name, status, started_at, ended_at, timezone_name,
                current_exercise_index, duration, total_sets, volume, xp, growth_delta, version)
               VALUES (?, ?, ?::jsonb, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?, ?, ?, ?, ?, ?, ?, 1)
               ON CONFLICT (owner_id, id) DO UPDATE SET payload = EXCLUDED.payload,
                 routine_id = EXCLUDED.routine_id, routine_name = EXCLUDED.routine_name, status = EXCLUDED.status,
                 started_at = EXCLUDED.started_at, ended_at = EXCLUDED.ended_at, timezone_name = EXCLUDED.timezone_name,
                 current_exercise_index = EXCLUDED.current_exercise_index, duration = EXCLUDED.duration,
                 total_sets = EXCLUDED.total_sets, volume = EXCLUDED.volume, xp = EXCLUDED.xp,
                 growth_delta = EXCLUDED.growth_delta, version = workouts.version + 1, updated_at = now()""".trimIndent(),
            ownerId, id, json(extras), payload.requiredString("routineId"), payload.string("routineName", "Workout"),
            payload.string("status", "IN_PROGRESS"), payload["startedAt"], payload["endedAt"],
            payload.string("timezone", "UTC"), payload.number("currentExerciseIndex").toInt(), payload["duration"],
            payload.number("totalSets", sets(payload).size.toDouble()).toInt(), payload.number("volume"),
            payload.number("xp").toInt(), payload.number("growthDelta").toInt(),
        )
        jdbcTemplate.update("DELETE FROM workout_sets WHERE owner_id = ? AND workout_id = ?", ownerId, id)
        sets(payload).forEach { set ->
            jdbcTemplate.update(
                """INSERT INTO workout_sets
                   (set_id, owner_id, workout_id, exercise_id, exercise_name, set_index, weight, reps, completed_at, is_pr)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?)""".trimIndent(),
                set.requiredString("setId"), ownerId, id, set.requiredString("exerciseId"), set["exerciseName"],
                set.number("setIndex").toInt(), set.number("weight"), set.number("reps").toInt(),
                set["completedAt"], set["pr"] ?: false,
            )
        }
    }

    private fun savePhotoJob(ownerId: String, id: String, payload: MutableMap<String, Any?>) {
        val options = payload.filterKeys { it !in setOf("jobId", "photoId", "kind", "status") }
        jdbcTemplate.update(
            """INSERT INTO photo_jobs (owner_id, id, payload, photo_id, job_type, status, options)
               VALUES (?, ?, '{}'::jsonb, ?, ?, ?, ?::jsonb)
               ON CONFLICT (owner_id, id) DO UPDATE SET photo_id = EXCLUDED.photo_id,
                 job_type = EXCLUDED.job_type, status = EXCLUDED.status, options = EXCLUDED.options, updated_at = now()""".trimIndent(),
            ownerId, id, payload.requiredString("photoId"), payload.string("kind", "FILTER"),
            payload.string("status", "PROCESSING"), json(options),
        )
    }

    private fun saveSimple(
        table: String,
        ownerId: String,
        id: String,
        payload: MutableMap<String, Any?>,
        columns: List<String>,
        values: List<Any?>,
        knownKeys: Set<String>,
        casts: Map<String, String> = emptyMap(),
    ) {
        val extra = payload.filterKeys { it !in knownKeys }
        val insertColumns = listOf("owner_id", "id", "payload") + columns
        val insertValues = listOf("?", "?", "?::jsonb") + columns.map { column ->
            casts[column]?.let { "?::$it" } ?: "?"
        }
        val updates = (listOf("payload = EXCLUDED.payload") + columns.map { "$it = EXCLUDED.$it" } + "updated_at = now()")
        val sql = "INSERT INTO $table (${insertColumns.joinToString()}) VALUES (${insertValues.joinToString()}) " +
            "ON CONFLICT (owner_id, id) DO UPDATE SET ${updates.joinToString()}"
        jdbcTemplate.update(sql, *(listOf(ownerId, id, json(extra)) + values).toTypedArray())
    }

    private fun legacy(ownerId: String, kind: String, id: String, forUpdate: Boolean): DomainRecord? {
        val lock = if (forUpdate) " FOR UPDATE" else ""
        val row = try {
            jdbcTemplate.queryForMap(
                "SELECT id, payload::text AS payload, created_at, updated_at FROM documents " +
                    "WHERE owner_id = ? AND kind = ? AND id = ?$lock",
                ownerId,
                kind,
                id,
            )
        } catch (_: EmptyResultDataAccessException) {
            return null
        }
        return DomainRecord(id, ownerId, jsonMap(row.string("payload")), row.instant("created_at"), row.instant("updated_at"))
    }

    private fun serialize(kind: String, row: Map<String, Any?>): DomainRecord {
        val id = row.string("id")
        val ownerId = row.string("owner_id")
        val payload = jsonMap(row.string("payload"))
        when (kind) {
            "profile" -> payload.putAll(mapOf("profileCompleted" to row["profile_completed"], "experienceLevel" to row["experience_level"], "goalType" to row["goal_type"], "weeklyWorkoutTarget" to row["weekly_workout_target"]))
            "settings" -> payload.putAll(mapOf("defaultRestSeconds" to row["default_rest_seconds"], "updatedAt" to row.instant("updated_at").toString()))
            "routine" -> {
                payload["routineId"] = id
                payload["name"] = row["name"]
                payload["exercises"] = jdbcTemplate.queryForList(
                    "SELECT * FROM routine_exercises WHERE owner_id = ? AND routine_id = ? ORDER BY order_index",
                    ownerId, id,
                ).map { exercise ->
                    jsonMap(exercise.string("extra")).apply {
                        putAll(mapOf("exerciseId" to exercise["exercise_id"], "sets" to exercise["set_count"], "reps" to exercise["reps"], "weight" to exercise["weight"], "restSeconds" to exercise["rest_seconds"], "order" to exercise["order_index"]))
                        exercise["name"]?.let { put("name", it) }
                    }
                }
                row["playlist"]?.let { payload["playlist"] = jsonMap(it.toString()) }
                payload.putIfAbsent("createdAt", row.instant("created_at").toString())
            }
            "workout" -> {
                payload.putAll(mapOf("workoutId" to id, "routineId" to row["routine_id"], "routineName" to row["routine_name"], "status" to row["status"], "startedAt" to row.instant("started_at").toString(), "endedAt" to row["ended_at"]?.let { it.instantString() }, "timezone" to row["timezone_name"], "currentExerciseIndex" to row["current_exercise_index"], "duration" to row["duration"], "totalSets" to row["total_sets"], "volume" to row["volume"], "xp" to row["xp"], "growthDelta" to row["growth_delta"], "version" to row["version"]))
                val sets = jdbcTemplate.queryForList(
                    "SELECT * FROM workout_sets WHERE owner_id = ? AND workout_id = ? ORDER BY completed_at, set_index",
                    ownerId, id,
                ).map { set ->
                    mapOf("setId" to set["set_id"], "exerciseId" to set["exercise_id"], "exerciseName" to set["exercise_name"], "setIndex" to set["set_index"], "weight" to set["weight"], "reps" to set["reps"], "completedAt" to set.instant("completed_at").toString(), "pr" to set["is_pr"])
                }
                payload["sets"] = sets
                payload["prs"] = sets.filter { it["pr"] == true }.map { mapOf("exerciseId" to it["exerciseId"], "weight" to it["weight"], "reps" to it["reps"]) }
            }
            "goal" -> payload.putAll(mapOf("goalId" to id, "goalType" to row["goal_type"], "startDate" to row["start_date"].toString(), "endDate" to row["end_date"].toString(), "weeklyWorkoutTarget" to row["weekly_workout_target"], "status" to row["status"], "growth" to row["growth"]))
            "weekly_goal" -> payload.putAll(mapOf("week" to row["week_start"].toString(), "targetCount" to row["target_count"], "completedCount" to row["completed_count"], "achieved" to row["achieved"], "goalId" to row["goal_id"]))
            "reward" -> payload.putAll(mapOf("workoutId" to row["workout_id"], "xp" to row["xp"], "growthDelta" to row["growth_delta"], "grantedAt" to row.instant("granted_at").toString()))
            "rest" -> payload.putAll(mapOf("restId" to id, "workoutId" to row["workout_id"], "setId" to row["set_id"], "status" to row["status"], "startedAt" to row.instant("started_at").toString(), "endsAt" to row.instant("ends_at").toString(), "durationSeconds" to row["duration_seconds"]))
            "photo" -> payload.putAll(mapOf("photoId" to id, "workoutId" to row["workout_id"], "fileName" to row["file_name"], "contentType" to row["content_type"], "size" to row["size"], "status" to row["status"], "storagePath" to row["storage_path"], "editedPath" to row["edited_path"], "capturedAt" to row.instant("captured_at").toString(), "expiresAt" to row.instant("expires_at").toString()))
            "photo_job" -> {
                payload.putAll(mapOf("jobId" to id, "photoId" to row["photo_id"], "kind" to row["job_type"], "status" to row["status"]))
                payload.putAll(jsonMap(row.string("options")))
            }
            "avatar_item" -> payload.putAll(mapOf("itemId" to id, "type" to row["item_type"], "unlocked" to row["unlocked"]))
        }
        return DomainRecord(id, ownerId, payload, row.instant("created_at"), row.instant("updated_at"))
    }

    private fun payloadId(kind: String, payload: Map<String, Any?>): String? = when (kind) {
        "profile", "settings" -> payload["userId"]?.toString() ?: "me"
        "routine" -> payload["routineId"]?.toString()
        "workout", "reward" -> payload["workoutId"]?.toString()
        "goal" -> payload["goalId"]?.toString()
        "weekly_goal" -> payload["week"]?.toString()
        "rest" -> payload["restId"]?.toString()
        "photo" -> payload["photoId"]?.toString()
        "photo_job" -> payload["jobId"]?.toString()
        "avatar_item" -> payload["itemId"]?.toString()
        else -> null
    }

    private fun json(value: Any?): String = objectMapper.writeValueAsString(value)

    private fun jsonMap(value: String): MutableMap<String, Any?> =
        objectMapper.readValue(value, object : TypeReference<MutableMap<String, Any?>>() {})

    private fun exercises(payload: Map<String, Any?>): List<Map<String, Any?>> =
        (payload["exercises"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    private fun sets(payload: Map<String, Any?>): List<Map<String, Any?>> =
        (payload["sets"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    private fun Map<String, Any?>.string(key: String, default: String = ""): String = this[key]?.toString() ?: default

    private fun Map<String, Any?>.requiredString(key: String): String =
        this[key]?.toString() ?: throw IllegalArgumentException("$key is required")

    private fun Map<String, Any?>.number(key: String, default: Double = 0.0): Double =
        when (val value = this[key]) {
            null -> default
            is Number -> value.toDouble()
            else -> value.toString().toDouble()
        }

    private fun Map<String, Any?>.string(key: String): String = this[key]?.toString() ?: ""

    private fun Map<String, Any?>.instant(key: String): Instant = when (val value = this[key]) {
        is Timestamp -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is Instant -> value
        else -> error("Missing timestamp column: $key")
    }

    private fun Any.instantString(): String = when (this) {
        is Timestamp -> toInstant().toString()
        is OffsetDateTime -> toInstant().toString()
        is Instant -> toString()
        else -> toString()
    }

    private companion object {
        val TABLES = mapOf(
            "profile" to "user_profiles",
            "settings" to "user_settings",
            "routine" to "routines",
            "workout" to "workouts",
            "goal" to "goals",
            "weekly_goal" to "weekly_goals",
            "reward" to "rewards",
            "rest" to "rest_timers",
            "photo" to "progress_photos",
            "photo_job" to "photo_jobs",
            "avatar_item" to "avatar_items",
        )
    }
}

private fun Map<String, Any?>.string(key: String): String = this[key]?.toString() ?: ""

private fun Map<String, Any?>.instant(key: String): Instant = when (val value = this[key]) {
    is Timestamp -> value.toInstant()
    is OffsetDateTime -> value.toInstant()
    is Instant -> value
    else -> error("Missing timestamp column: $key")
}
