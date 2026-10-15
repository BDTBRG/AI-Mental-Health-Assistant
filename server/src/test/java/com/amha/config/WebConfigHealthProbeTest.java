package com.amha.config;

import com.amha.interceptor.AdminAuthorizationInterceptor;
import com.amha.interceptor.JwtInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WebConfigHealthProbeTest {

    private static AnnotationConfigWebApplicationContext applicationContext;
    private static RequestMappingHandlerMapping handlerMapping;

    @BeforeAll
    static void startMvcContext() {
        applicationContext = new AnnotationConfigWebApplicationContext();
        applicationContext.setServletContext(new MockServletContext());
        applicationContext.register(MvcConfiguration.class);
        applicationContext.refresh();
        handlerMapping = applicationContext.getBean(RequestMappingHandlerMapping.class);
    }

    @AfterAll
    static void closeMvcContext() {
        applicationContext.close();
    }

    @Test
    void actuatorHealthProbesBypassJwtAndAdminInterceptors() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/actuator/health/readiness");

        assertFalse(isMapped(JwtInterceptor.class, request));
        assertFalse(isMapped(AdminAuthorizationInterceptor.class, request));
    }

    @Test
    void applicationRoutesRemainCoveredByAuthenticationInterceptors() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/profile");

        assertTrue(isMapped(JwtInterceptor.class, request));
        assertTrue(isMapped(AdminAuthorizationInterceptor.class, request));
    }

    private boolean isMapped(Class<? extends HandlerInterceptor> interceptorType,
                             MockHttpServletRequest request) {
        ServletRequestPathUtils.parseAndCache(request);
        return Arrays.stream(handlerMapping.getAdaptedInterceptors())
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .anyMatch(mapped -> interceptorType.isInstance(mapped.getInterceptor())
                        && mapped.matches(request));
    }

    @Configuration
    @EnableWebMvc
    @Import(WebConfig.class)
    static class MvcConfiguration {
        @Bean
        JwtInterceptor jwtInterceptor() {
            return mock(JwtInterceptor.class);
        }

        @Bean
        AdminAuthorizationInterceptor adminAuthorizationInterceptor() {
            return mock(AdminAuthorizationInterceptor.class);
        }
    }
}
