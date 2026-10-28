package com.teamnative.gymup.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.teamnative.gymup.api.ApiErrorDetail
import com.teamnative.gymup.api.ApiErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class JwtAuthenticationInterceptor(
    private val jwtService: JwtService,
    private val objectMapper: ObjectMapper,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val authorization = request.getHeader("Authorization")
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
        val subject = token?.let(jwtService::subject)

        if (subject == null) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.setHeader("WWW-Authenticate", "Bearer")
            response.contentType = "application/json"
            response.characterEncoding = "UTF-8"
            objectMapper.writeValue(
                response.outputStream,
                ApiErrorResponse(
                    detail = ApiErrorDetail(
                        code = "UNAUTHORIZED",
                        message = if (token == null) {
                            "Bearer token is required"
                        } else {
                            "Bearer token is invalid or expired"
                        },
                    ),
                ),
            )
            return false
        }

        request.setAttribute(USER_ID_ATTRIBUTE, subject)
        return true
    }

    companion object {
        const val USER_ID_ATTRIBUTE = "gymup.userId"
    }
}
