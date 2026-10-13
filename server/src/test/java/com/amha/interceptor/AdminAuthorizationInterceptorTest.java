package com.amha.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthorizationInterceptorTest {

    private final AdminAuthorizationInterceptor interceptor = new AdminAuthorizationInterceptor();

    @Test
    void fileUploadRequiresAdministratorRole() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/file/upload");
        request.setAttribute("userType", 1);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getContentAsString().contains("\"code\":\"403\""));
    }

    @Test
    void administratorCanUploadArticleCover() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/file/upload");
        request.setAttribute("userType", 2);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }
}
