package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainRecord
import com.teamnative.gymup.db.DomainStore
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@RestController
@RequestMapping("/api/v1")
class PhotoController(
    private val domainStore: DomainStore,
    @Value("\${gymup.jwt.secret}") private val jwtSecret: String,
    @Value("\${gymup.public-base-url}") private val publicBaseUrl: String,
    @Value("\${gymup.media-root}") mediaRoot: String,
) {
    private val mediaDirectory: Path = Path.of(mediaRoot)

    @PostMapping("/workouts/{workoutId}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    fun preparePhoto(
        @PathVariable workoutId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val workout = domainStore.get(userId, "workout", workoutId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "WORKOUT_NOT_FOUND", "Workout not found")
        if (workout.payload["status"] != "COMPLETED") {
            throw ApiException(HttpStatus.CONFLICT, "INVALID_WORKOUT_STATE", "Photo can be attached only to a completed workout")
        }
        required(body, "fileName", "contentType", "size")
        val size = body.int("size")
        val contentType = body["contentType"].toString()
        if (size <= 0) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "File size must be positive")
        if (size > MAX_PHOTO_BYTES) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "Maximum photo size is 20 MiB")
        if (contentType !in ACCEPTED_CONTENT_TYPES) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "Only JPEG, PNG, or WebP are accepted")
        }

        val photoId = UUID.randomUUID().toString()
        val now = Instant.now()
        val expiresAt = now.plusSeconds(UPLOAD_TTL_SECONDS)
        val url = "$publicBaseUrl/api/v1/photos/$photoId/content"
        val payload = mutableMapOf<String, Any?>(
            "photoId" to photoId,
            "workoutId" to workoutId,
            "fileName" to body["fileName"],
            "contentType" to contentType,
            "size" to size,
            "status" to "READY_FOR_UPLOAD",
            "capturedAt" to now.toString(),
            "expiresAt" to expiresAt.toString(),
        )
        domainStore.save(userId, "photo", payload, photoId)
        return mapOf(
            "photoId" to photoId,
            "uploadUrl" to url,
            "uploadHeaders" to mapOf("Content-Type" to contentType),
            "expiresAt" to expiresAt.toString(),
        )
    }

    @PutMapping("/photos/{photoId}/content")
    @Transactional
    fun uploadContent(
        @PathVariable photoId: String,
        @RequestBody content: ByteArray,
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        val userId = userId(request)
        val photo = ownedPhoto(userId, photoId)
        if (dateTime(photo.payload["expiresAt"]).isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw ApiException(HttpStatus.FORBIDDEN, "UPLOAD_URL_EXPIRED", "Upload URL expired")
        }
        if (content.size != photo.payload.int("size")) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "Uploaded size does not match metadata")
        }
        val extension = FILE_EXTENSIONS[photo.payload["contentType"]]
            ?: throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "Unsupported file type")
        Files.createDirectories(mediaDirectory)
        val path = mediaDirectory.resolve("$photoId$extension")
        Files.write(path, content)
        val payload = photo.payload.toMutableMap().apply {
            put("status", "READY")
            put("storagePath", path.toString())
            put("uploadedAt", nowIso())
        }
        domainStore.save(userId, "photo", payload, photoId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/photos/{photoId}/download-url")
    fun downloadUrl(@PathVariable photoId: String, request: HttpServletRequest): Map<String, Any?> {
        val photo = ownedPhoto(userId(request), photoId)
        if (photo.payload["status"] != "READY") {
            throw ApiException(HttpStatus.CONFLICT, "PHOTO_NOT_READY", "Photo is not ready")
        }
        val expires = Instant.now().plusSeconds(DOWNLOAD_TTL_SECONDS).epochSecond
        val token = signDownload(photoId, photo.ownerId, expires)
        val url = "$publicBaseUrl/api/v1/photos/$photoId/download?expires=$expires&token=$token"
        return mapOf(
            "photoId" to photoId,
            "downloadUrl" to url,
            "expiresAt" to Instant.ofEpochSecond(expires).toString(),
        )
    }

    @GetMapping("/photos/{photoId}/download")
    fun downloadContent(
        @PathVariable photoId: String,
        @RequestParam expires: Long,
        @RequestParam token: String,
    ): ResponseEntity<ByteArray> {
        val photo = domainStore.getPhotoById(photoId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "Photo not found")
        val expected = signDownload(photoId, photo.ownerId, expires)
        if (expires < Instant.now().epochSecond || !MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())) {
            throw ApiException(HttpStatus.FORBIDDEN, "DOWNLOAD_URL_EXPIRED", "Download URL is invalid or expired")
        }
        val pathValue = photo.payload["editedPath"]?.toString()
            ?: photo.payload["storagePath"]?.toString().orEmpty()
        val path = Path.of(pathValue)
        if (!Files.isRegularFile(path)) {
            throw ApiException(HttpStatus.CONFLICT, "PHOTO_NOT_READY", "Photo file is missing")
        }
        val fileName = Path.of(photo.payload["fileName"].toString()).fileName.toString().replace("\"", "")
        val mediaType = if (photo.payload["editedPath"] != null) MediaType.IMAGE_JPEG
        else MediaType.parseMediaType(photo.payload["contentType"].toString())
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$fileName\"")
            .body(Files.readAllBytes(path))
    }

    @GetMapping("/photos")
    fun photoAlbum(
        request: HttpServletRequest,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "desc") sort: String,
    ): Map<String, Any?> {
        if (size !in 1..100) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "size must be between 1 and 100")
        var photos = domainStore.list(userId(request), "photo")
            .filter { from == null || dateOf(it.payload["capturedAt"]) >= from }
            .filter { to == null || dateOf(it.payload["capturedAt"]) <= to }
        if (sort.equals("asc", ignoreCase = true)) photos = photos.reversed()
        val offset = decodeCursor(cursor)
        val page = photos.drop(offset).take(size)
        return mapOf(
            "items" to page.map { photo ->
                mapOf("photoId" to photo.id, "workoutId" to photo.payload["workoutId"], "capturedAt" to photo.payload["capturedAt"], "thumbnailUrl" to null)
            },
            "nextCursor" to encodeCursor(offset, size, photos.size),
        )
    }

    @PostMapping("/photos/{photoId}/filters")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    fun applyFilter(
        @PathVariable photoId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val photo = ownedPhoto(userId, photoId)
        requireReady(photo)
        requireNoActiveJob(userId, photoId)
        val preset = body["preset"]?.toString()
        if (preset !in FILTER_PRESETS) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "Unknown filter preset")
        val jobId = UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any?>(
            "jobId" to jobId,
            "photoId" to photoId,
            "kind" to "FILTER",
            "preset" to preset,
            "adjustments" to (body["adjustments"] ?: emptyMap<String, Any>()),
            "status" to "QUEUED",
            "createdAt" to nowIso(),
        )
        domainStore.save(userId, "photo_job", payload, jobId)
        return mapOf("jobId" to jobId, "photoId" to photoId, "status" to "PROCESSING")
    }

    @PostMapping("/photos/{photoId}/stamps")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    fun applyStamp(
        @PathVariable photoId: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val userId = userId(request)
        val photo = ownedPhoto(userId, photoId)
        requireReady(photo)
        requireNoActiveJob(userId, photoId)
        if (!body.containsKey("layoutId")) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_LAYOUT", "layoutId is required")
        val jobId = UUID.randomUUID().toString()
        val payload = mutableMapOf<String, Any?>(
            "jobId" to jobId,
            "photoId" to photoId,
            "kind" to "STAMP",
            "layoutId" to body["layoutId"],
            "fields" to (body["fields"] ?: emptyList<Any>()),
            "status" to "QUEUED",
            "createdAt" to nowIso(),
        )
        domainStore.save(userId, "photo_job", payload, jobId)
        return mapOf("jobId" to jobId, "photoId" to photoId, "status" to "PROCESSING")
    }

    @GetMapping("/photos/jobs/{jobId}")
    fun photoJobStatus(@PathVariable jobId: String, request: HttpServletRequest): Map<String, Any?> {
        val job = domainStore.get(userId(request), "photo_job", jobId, forUpdate = false)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Photo job not found")
        val status = job.payload["status"]?.toString()
        return mapOf(
            "jobId" to jobId,
            "photoId" to job.payload["photoId"],
            "status" to if (status == "QUEUED" || status == "PROCESSING") "PROCESSING" else status,
            "error" to job.payload["error"],
            "completedAt" to job.payload["completedAt"],
        )
    }

    private fun ownedPhoto(userId: String, photoId: String): DomainRecord =
        domainStore.get(userId, "photo", photoId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "PHOTO_NOT_FOUND", "Photo not found")

    private fun requireReady(photo: DomainRecord) {
        if (photo.payload["status"] != "READY") {
            throw ApiException(HttpStatus.CONFLICT, "PHOTO_NOT_READY", "Photo is not ready")
        }
    }

    private fun requireNoActiveJob(userId: String, photoId: String) {
        val active = domainStore.list(userId, "photo_job").any { job ->
            job.payload["photoId"] == photoId && job.payload["status"] in setOf("QUEUED", "PROCESSING")
        }
        if (active) throw ApiException(HttpStatus.CONFLICT, "PROCESSING", "A photo edit is already in progress")
    }

    private fun signDownload(photoId: String, ownerId: String, expires: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$photoId:$ownerId:$expires".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun decodeCursor(cursor: String?): Int {
        if (cursor == null) return 0
        return try {
            Base64.getUrlDecoder().decode(cursor).toString(Charsets.UTF_8).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "cursor is invalid")
        }
    }

    private fun encodeCursor(offset: Int, size: Int, total: Int): String? =
        if (offset + size < total) Base64.getUrlEncoder().withoutPadding().encodeToString((offset + size).toString().toByteArray()) else null

    private fun dateTime(value: Any?): OffsetDateTime = OffsetDateTime.parse(value.toString())

    private fun dateOf(value: Any?): LocalDate = LocalDate.parse(value.toString().take(10))

    private fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()

    private fun userId(request: HttpServletRequest): String =
        request.getAttribute(JwtAuthenticationInterceptor.USER_ID_ATTRIBUTE) as? String
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Bearer token is required")

    private fun required(body: Map<String, Any?>, vararg keys: String) {
        val missing = keys.filterNot(body::containsKey)
        if (missing.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILE", "${missing.first()} is required")
        }
    }

    private fun Map<String, Any?>.int(key: String): Int =
        (this[key] as? Number)?.toInt() ?: this[key]?.toString()?.toIntOrNull() ?: 0

    private companion object {
        const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
        const val UPLOAD_TTL_SECONDS = 15 * 60L
        const val DOWNLOAD_TTL_SECONDS = 10 * 60L
        val ACCEPTED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val FILE_EXTENSIONS = mapOf("image/jpeg" to ".jpg", "image/png" to ".png", "image/webp" to ".webp")
        val FILTER_PRESETS = setOf("Natural", "Pump", "Definition", "Mirror")
    }
}
