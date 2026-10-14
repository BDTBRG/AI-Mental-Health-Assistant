package com.amha.interceptor;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthorizationInterceptorTest {
    private final AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor();

    @Test
    void knowledgeIndexRebuildRejectsRegularUsers() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knowledge/index/rebuild");
        request.setAttribute("userType", 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"403\""));
    }

    @Test
    void knowledgeIndexRebuildAllowsAdmins() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knowledge/index/rebuild");
        request.setAttribute("userType", 2);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}
