package com.amha.common;

/** Parses the numeric session identifier accepted by chat APIs. */
public final class SessionIdParser {
    private static final String FRONTEND_PREFIX = "session_";

    private SessionIdParser() {
    }

    public static Long parse(String value) {
        if (value == null || value.isBlank()) {
            throw invalidSessionId();
        }

        String numericValue = value.startsWith(FRONTEND_PREFIX)
                ? value.substring(FRONTEND_PREFIX.length())
                : value;
        if (!numericValue.matches("[0-9]{1,19}")) {
            throw invalidSessionId();
        }

        try {
            long sessionId = Long.parseLong(numericValue);
            if (sessionId > 0) {
                return sessionId;
            }
        } catch (NumberFormatException ignored) {
            // The numeric value is outside the range supported by the database ID type.
        }
        throw invalidSessionId();
    }

    private static BusinessException invalidSessionId() {
        return new BusinessException("400", "会话ID格式无效");
    }
}
