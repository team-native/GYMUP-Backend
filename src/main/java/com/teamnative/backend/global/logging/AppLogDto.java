package com.teamnative.backend.global.logging;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AppLogDto(
        Instant timestamp,
        LogLevel level,
        LogEvent event,
        String message,
        String traceId,
        ServerLogData server,
        HttpLogData http,
        ErrorLogData error
) {
    public AppLogDto(
            LogLevel level,
            LogEvent event,
            String message,
            String traceId,
            ServerLogData server,
            HttpLogData http,
            ErrorLogData error
    ) {
        this(Instant.now(), level, event, message, traceId, server, http, error);
    }

    public AppLogDto withLevel(LogLevel level) {
        return new AppLogDto(timestamp, level, event, message, traceId, server, http, error);
    }

    public record ServerLogData(
            String application,
            String port,
            List<String> profiles
    ) {
    }

    public record HttpLogData(
            String method,
            String path,
            String query,
            Integer status,
            Long durationMs,
            String clientIp,
            PayloadLogData request,
            PayloadLogData response
    ) {
    }

    public record PayloadLogData(
            Map<String, String> headers,
            Object body
    ) {
        public PayloadLogData {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    public record ErrorLogData(
            String type,
            String message,
            Integer status
    ) {
    }
}
