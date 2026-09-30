package com.teamnative.backend.global.logging;

import com.teamnative.backend.global.logging.AppLogDto.ErrorLogData;
import com.teamnative.backend.global.logging.AppLogDto.HttpLogData;
import com.teamnative.backend.global.logging.AppLogDto.PayloadLogData;
import com.teamnative.backend.global.logging.AppLogDto.ServerLogData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AppLogger {

    private final Logger logger = LoggerFactory.getLogger("backend.application");

    public void info(AppLogDto log) {
        logger.info(toMessage(log.withLevel(LogLevel.INFO)));
    }

    public void warn(AppLogDto log) {
        logger.warn(toMessage(log.withLevel(LogLevel.WARN)));
    }

    public void error(AppLogDto log) {
        logger.error(toMessage(log.withLevel(LogLevel.ERROR)));
    }

    public void error(AppLogDto log, Throwable exception) {
        String payload = toMessage(log.withLevel(LogLevel.ERROR));
        if (exception == null) {
            logger.error(payload);
            return;
        }

        logger.error(payload, exception);
    }

    private String toMessage(AppLogDto log) {
        StringBuilder message = new StringBuilder()
                .append("[")
                .append(log.event())
                .append("] ")
                .append(log.message());

        append(message, "traceId", log.traceId());
        appendServer(message, log.server());
        appendHttp(message, log.http());
        appendError(message, log.error());

        return message.toString();
    }

    private void appendServer(StringBuilder message, ServerLogData server) {
        if (server == null) {
            return;
        }

        append(message, "application", server.application());
        append(message, "port", server.port());
        append(message, "profiles", server.profiles() == null || server.profiles().isEmpty()
                ? "default"
                : String.join(",", server.profiles()));
    }

    private void appendHttp(StringBuilder message, HttpLogData http) {
        if (http == null) {
            return;
        }

        append(message, "method", http.method());
        append(message, "path", http.path());
        append(message, "query", http.query());
        append(message, "status", http.status());
        append(message, "durationMs", http.durationMs());
        append(message, "clientIp", http.clientIp());
        appendPayload(message, "request", http.request());
        appendPayload(message, "response", http.response());
    }

    private void appendPayload(StringBuilder message, String prefix, PayloadLogData payload) {
        if (payload == null) {
            return;
        }

        append(message, prefix + "Headers", payload.headers());
        append(message, prefix + "Body", payload.body());
    }

    private void appendError(StringBuilder message, ErrorLogData error) {
        if (error == null) {
            return;
        }

        append(message, "errorType", error.type());
        append(message, "errorStatus", error.status());
        append(message, "errorMessage", error.message());
    }

    private void append(StringBuilder message, String key, Object value) {
        if (value == null) {
            return;
        }

        message.append(" ")
                .append(key)
                .append("=")
                .append(value);
    }
}
