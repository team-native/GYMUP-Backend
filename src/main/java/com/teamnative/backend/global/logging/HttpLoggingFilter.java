package com.teamnative.backend.global.logging;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.teamnative.backend.global.logging.AppLogDto.ErrorLogData;
import com.teamnative.backend.global.logging.AppLogDto.HttpLogData;
import com.teamnative.backend.global.logging.AppLogDto.PayloadLogData;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class HttpLoggingFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = "backend.traceId";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final int MAX_BODY_LENGTH = 2_000;
    private static final String REDACTED_BODY_VALUE = "[REDACTED_BODY_CONTAINS_SENSITIVE_FIELD]";
    private static final List<String> LOGGABLE_HEADERS = List.of(
            "Content-Type",
            "Content-Length",
            "Accept",
            TRACE_ID_HEADER
    );

    private final AppLogger appLogger;
    private final ObjectMapper objectMapper;

    public HttpLoggingFilter(AppLogger appLogger, ObjectMapper objectMapper) {
        this.appLogger = appLogger;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = traceId(request);
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request, MAX_BODY_LENGTH);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long startedAt = System.currentTimeMillis();
        wrappedRequest.setAttribute(TRACE_ID_ATTRIBUTE, traceId);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } catch (IOException | ServletException | RuntimeException exception) {
            logError(traceId, wrappedRequest, startedAt, exception);
            throw exception;
        } finally {
            long durationMs = System.currentTimeMillis() - startedAt;
            wrappedResponse.setHeader(TRACE_ID_HEADER, traceId);
            logRequest(traceId, wrappedRequest);
            logResponse(traceId, wrappedRequest, wrappedResponse, durationMs);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void logRequest(String traceId, ContentCachingRequestWrapper request) {
        appLogger.info(new AppLogDto(
                LogLevel.INFO,
                LogEvent.EXTERNAL_REQUEST,
                "Incoming HTTP request.",
                traceId,
                null,
                new HttpLogData(
                        request.getMethod(),
                        request.getRequestURI(),
                        sanitizeQuery(request.getQueryString()),
                        null,
                        null,
                        clientIp(request),
                        new PayloadLogData(
                                loggableHeaders(Collections.list(request.getHeaderNames()).stream()
                                        .collect(Collectors.toMap(
                                                header -> header,
                                                request::getHeader,
                                                (left, right) -> right,
                                                LinkedHashMap::new
                                        ))),
                                parseBody(request.getContentAsByteArray(), request.getCharacterEncoding())
                        ),
                        null
                ),
                null
        ));
    }

    private void logResponse(
            String traceId,
            HttpServletRequest request,
            ContentCachingResponseWrapper response,
            long durationMs
    ) {
        int status = response.getStatus();
        AppLogDto log = new AppLogDto(
                status >= 500 ? LogLevel.ERROR : LogLevel.INFO,
                LogEvent.RESPONSE_RETURNED,
                "HTTP response completed.",
                traceId,
                null,
                new HttpLogData(
                        request.getMethod(),
                        request.getRequestURI(),
                        sanitizeQuery(request.getQueryString()),
                        status,
                        durationMs,
                        clientIp(request),
                        null,
                        new PayloadLogData(
                                loggableHeaders(response.getHeaderNames().stream()
                                        .collect(Collectors.toMap(
                                                header -> header,
                                                header -> response.getHeader(header) == null ? "" : response.getHeader(header),
                                                (left, right) -> right,
                                                LinkedHashMap::new
                                        ))),
                                parseBody(response.getContentAsByteArray(), response.getCharacterEncoding())
                        )
                ),
                null
        );

        if (status >= 500) {
            appLogger.error(log);
            return;
        }

        appLogger.info(log);
    }

    private void logError(
            String traceId,
            HttpServletRequest request,
            long startedAt,
            Exception exception
    ) {
        appLogger.error(new AppLogDto(
                LogLevel.ERROR,
                LogEvent.ERROR_OCCURRED,
                "Unhandled HTTP request error.",
                traceId,
                null,
                new HttpLogData(
                        request.getMethod(),
                        request.getRequestURI(),
                        sanitizeQuery(request.getQueryString()),
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        System.currentTimeMillis() - startedAt,
                        clientIp(request),
                        null,
                        null
                ),
                new ErrorLogData(
                        exception.getClass().getName(),
                        exception.getMessage(),
                        HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                )
        ), exception);
    }

    private String traceId(HttpServletRequest request) {
        String headerTraceId = request.getHeader(TRACE_ID_HEADER);
        return headerTraceId == null || headerTraceId.isBlank()
                ? UUID.randomUUID().toString()
                : headerTraceId;
    }

    private Map<String, String> loggableHeaders(Map<String, String> headers) {
        Map<String, String> loggable = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            boolean allowed = LOGGABLE_HEADERS.stream().anyMatch(header -> header.equalsIgnoreCase(key));
            if (!allowed) {
                return;
            }

            loggable.put(key, LogSanitizer.isSensitive(key) ? LogSanitizer.MASKED_VALUE : value);
        });
        return loggable;
    }

    private Object parseBody(byte[] bytes, String encoding) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        Charset charset = charset(encoding);
        String body = new String(bytes, charset);
        String limitedBody = body.length() > MAX_BODY_LENGTH ? body.substring(0, MAX_BODY_LENGTH) : body;

        try {
            return sanitizeBody(objectMapper.readValue(limitedBody, Object.class));
        } catch (JacksonException ignored) {
            return sanitizeRawBody(limitedBody);
        }
    }

    private Object sanitizeBody(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, entryValue) -> {
                String keyText = key == null ? "" : key.toString();
                sanitized.put(keyText, LogSanitizer.isSensitive(keyText)
                        ? LogSanitizer.MASKED_VALUE
                        : sanitizeBody(entryValue));
            });
            return sanitized;
        }

        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(this::sanitizeBody)
                    .toList();
        }

        return value;
    }

    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }

        return List.of(query.split("&")).stream()
                .map(this::sanitizeQueryPart)
                .collect(Collectors.joining("&"));
    }

    private String sanitizeQueryPart(String part) {
        String key = part.contains("=") ? part.substring(0, part.indexOf("=")) : part;
        String decodedKey = decodeUrlValue(key);
        return LogSanitizer.isSensitive(decodedKey)
                ? key + "=" + LogSanitizer.MASKED_VALUE
                : part;
    }

    private String sanitizeRawBody(String body) {
        return LogSanitizer.SENSITIVE_KEYS.stream()
                .anyMatch(key -> body.toLowerCase().contains(key.toLowerCase()))
                ? REDACTED_BODY_VALUE
                : body;
    }

    private String decodeUrlValue(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private Charset charset(String encoding) {
        try {
            return Charset.forName(encoding == null ? StandardCharsets.UTF_8.name() : encoding);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }
}
