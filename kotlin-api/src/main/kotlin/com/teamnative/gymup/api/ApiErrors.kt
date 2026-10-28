package com.teamnative.gymup.api

import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException

data class ApiErrorDetail(
    val code: String,
    val message: String,
)

data class ApiErrorResponse(
    val detail: ApiErrorDetail,
)

class ApiException(
    val status: HttpStatus,
    val code: String,
    override val message: String,
) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(exception.status).body(
            ApiErrorResponse(
                detail = ApiErrorDetail(
                    code = exception.code,
                    message = exception.message,
                ),
            ),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse(
                detail = ApiErrorDetail(
                    code = "VALIDATION_ERROR",
                    message = "The request body is invalid",
                ),
            ),
        )

    @ExceptionHandler(DataAccessException::class)
    fun databaseUnavailable(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ApiErrorResponse(
                detail = ApiErrorDetail(
                    code = "DATABASE_UNAVAILABLE",
                    message = "Database is unavailable",
                ),
            ),
        )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrityConflict(): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ApiErrorResponse(
                detail = ApiErrorDetail(
                    code = "CONFLICT",
                    message = "The resource changed or already exists",
                ),
            ),
        )
}
