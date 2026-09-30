package com.teamnative.backend.global.logging;

import java.util.List;

public final class LogSanitizer {

    public static final String MASKED_VALUE = "***";
    public static final List<String> SENSITIVE_KEYS = List.of(
            "authorization",
            "password",
            "pwd",
            "token",
            "secret",
            "cookie"
    );

    private LogSanitizer() {
    }

    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }

        String lowerKey = key.toLowerCase();
        return SENSITIVE_KEYS.stream()
                .map(String::toLowerCase)
                .anyMatch(lowerKey::contains);
    }
}
