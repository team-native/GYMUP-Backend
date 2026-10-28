package com.teamnative.gymup.api

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(
    val status: String,
    val database: String,
)

@RestController
@RequestMapping("/api/v1")
class HealthController(
    private val jdbcTemplate: JdbcTemplate,
) {
    @GetMapping("/health")
    fun health(): HealthResponse {
        jdbcTemplate.queryForObject("SELECT 1", Int::class.java)
        return HealthResponse(status = "ok", database = "ok")
    }
}
