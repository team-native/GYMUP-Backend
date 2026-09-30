package com.teamnative.backend.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @Schema(description = "아이디", example = "user1")
        @NotBlank(message = "Username is required.")
        String username,

        @Schema(description = "비밀번호", example = "password123")
        @NotBlank(message = "Password is required.")
        String password
) {
}
