package com.amha.interceptor;

import com.amha.config.JwtUtil;
import com.amha.entity.User;
import com.amha.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JwtInterceptorTest.TestConfiguration.class)
class JwtInterceptorTest {

    private static final String SECRET = "unit-test-jwt-secret-with-at-least-32-bytes";

    @org.springframework.beans.factory.annotation.Autowired
    private JwtInterceptor interceptor;

    @org.springframework.beans.factory.annotation.Autowired
    private JwtUtil jwtUtil;

    @org.springframework.beans.factory.annotation.Autowired
    private RedisTemplate<String, String> redisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private UserMapper userMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void resetMocks() {
        reset(redisTemplate, userMapper, valueOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void rejectsPreviouslyIssuedTokenAfterItsRedisSessionWasRevoked() throws Exception {
        String token = jwtUtil.generateToken(7L, "test-user", 1);
        when(valueOperations.get("token:" + token)).thenReturn(null);

        MockHttpServletRequest request = requestWithToken(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertTrue(response.getContentAsString().contains("\"code\":\"-1\""));
        verifyNoInteractions(userMapper);
    }

    @Test
    void rejectsTokenWhenRedisSessionBelongsToAnotherUser() throws Exception {
        String token = jwtUtil.generateToken(7L, "test-user", 1);
        when(valueOperations.get("token:" + token)).thenReturn("8");

        assertFalse(interceptor.preHandle(requestWithToken(token), new MockHttpServletResponse(), new Object()));
        verifyNoInteractions(userMapper);
    }

    @Test
    void failsClosedWithServiceUnavailableWhenRedisCannotValidateSession() throws Exception {
        String token = jwtUtil.generateToken(7L, "test-user", 1);
        when(valueOperations.get("token:" + token))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken(token), response, new Object()));
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"503\""));
        verifyNoInteractions(userMapper);
    }

    @Test
    void rejectsTokenForDisabledAccount() throws Exception {
        String token = jwtUtil.generateToken(7L, "test-user", 1);
        when(valueOperations.get("token:" + token)).thenReturn("7");
        User disabled = user(7L, 0, 1);
        when(userMapper.selectById(7L)).thenReturn(disabled);

        assertFalse(interceptor.preHandle(requestWithToken(token), new MockHttpServletResponse(), new Object()));
    }

    @Test
    void usesCurrentDatabaseRoleInsteadOfStaleRoleClaim() throws Exception {
        String token = jwtUtil.generateToken(7L, "test-user", 1);
        when(valueOperations.get("token:" + token)).thenReturn("7");
        when(userMapper.selectById(7L)).thenReturn(user(7L, 1, 2));
        MockHttpServletRequest request = requestWithToken(token);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertEquals(2, request.getAttribute("userType"));
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/protected");
        request.addHeader("token", token);
        return request;
    }

    private static User user(Long id, int status, int userType) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        user.setUserType(userType);
        return user;
    }

    @Configuration
    @Import(JwtInterceptor.class)
    static class TestConfiguration {
        @Bean
        JwtUtil jwtUtil() {
            return new JwtUtil(SECRET, 60_000L);
        }

        @Bean
        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redisTemplate() {
            return mock(RedisTemplate.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations() {
            return mock(ValueOperations.class);
        }

        @Bean
        UserMapper userMapper() {
            return mock(UserMapper.class);
        }
    }
}
