package com.teamnative.gymup.core

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class IssuedToken(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: String,
)

@Service
class JwtService(
    @Value("\${gymup.jwt.secret}") private val secret: String,
    private val objectMapper: ObjectMapper,
) {
    fun issue(userId: String): IssuedToken {
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plusSeconds(TOKEN_TTL_SECONDS)
        val header = encodeJson(mapOf("alg" to "HS256", "typ" to "JWT"))
        val payload = encodeJson(
            mapOf(
                "sub" to userId,
                "iat" to issuedAt.epochSecond,
                "exp" to expiresAt.epochSecond,
            ),
        )
        val signingInput = "$header.$payload"
        val token = "$signingInput.${Base64.getUrlEncoder().withoutPadding().encodeToString(sign(signingInput))}"

        return IssuedToken(
            accessToken = token,
            tokenType = "Bearer",
            expiresAt = OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC).toString(),
        )
    }

    fun subject(token: String): String? {
        val segments = token.split('.')
        if (segments.size != 3) return null

        return try {
            val header = objectMapper.readTree(decode(segments[0]))
            if (header.path("alg").asText() != "HS256") return null

            val expected = sign("${segments[0]}.${segments[1]}")
            val supplied = decode(segments[2])
            if (!MessageDigest.isEqual(expected, supplied)) return null

            val claims = objectMapper.readTree(decode(segments[1]))
            val subject = claims.path("sub").asText().takeIf(String::isNotBlank) ?: return null
            val expiration = claims.path("exp").takeIf { it.isIntegralNumber }?.asLong() ?: return null
            val issuedAt = claims.path("iat").takeIf { it.isIntegralNumber }?.asLong() ?: return null
            val now = Instant.now().epochSecond
            if (expiration <= now || issuedAt > now) return null

            subject
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeJson(value: Any): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(value))

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun sign(value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(UTF_8))
    }

    private companion object {
        const val TOKEN_TTL_SECONDS = 12 * 60 * 60L
    }
}
