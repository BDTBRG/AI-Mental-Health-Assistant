package com.amha.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionIdParserTest {

    @Test
    void acceptsPositiveNumericIdsAndFrontendSessionPrefix() {
        assertEquals(123L, SessionIdParser.parse("123"));
        assertEquals(123L, SessionIdParser.parse("session_123"));
    }

    @Test
    void rejectsMissingMalformedAndNonPositiveIdsAsBadRequests() {
        for (String value : new String[]{null, "", "  ", "session_", "abc", "session_1x", "0", "-1",
                "9223372036854775808"}) {
            BusinessException error = assertThrows(BusinessException.class, () -> SessionIdParser.parse(value));
            assertEquals("400", error.getCode(), "value=" + value);
        }
    }
}
