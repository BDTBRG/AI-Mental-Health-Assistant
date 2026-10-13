package com.amha.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {

    @Test
    void rejectsMissingSecretWithConfigurationError() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new JwtUtil("   ", 60_000L)
        );

        assertTrue(error.getMessage().contains("jwt.secret"));
    }

    @Test
    void rejectsSecretsShorterThan256Bits() {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new JwtUtil("too-short", 60_000L)
        );

        assertTrue(error.getMessage().contains("32"));
    }

    @Test
    void acceptsStrongSecretAndSignsTokens() {
        JwtUtil jwtUtil = new JwtUtil("local-test-secret-with-at-least-32-bytes", 60_000L);

        String token = jwtUtil.generateToken(7L, "test-user", 1);

        assertEquals(7L, jwtUtil.parseToken(token).get("userId", Long.class));
    }
}
