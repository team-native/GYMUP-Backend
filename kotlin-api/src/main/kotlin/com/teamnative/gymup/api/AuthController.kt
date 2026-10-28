package com.teamnative.gymup.api

import com.teamnative.gymup.core.IssuedToken
import com.teamnative.gymup.core.JwtService
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DevTokenRequest(
    @field:Size(min = 1, max = 128)
    val userId: String? = null,
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val jwtService: JwtService,
    @Value("\${gymup.environment:production}") private val environment: String,
) {
    @PostMapping("/dev-token")
    fun issueDevToken(@Valid @RequestBody body: DevTokenRequest): IssuedToken {
        if (environment != "development") {
            throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found")
        }

        return jwtService.issue(body.userId ?: "local-user")
    }
}
