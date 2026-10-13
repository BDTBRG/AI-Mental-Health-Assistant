package com.amha.interceptor;

import com.amha.config.JwtUtil;
import com.amha.entity.User;
import com.amha.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_KNOWLEDGE_READS = Set.of(
            "/knowledge/category/tree",
            "/knowledge/article/page"
    );

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;
    private final UserMapper userMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            if (isPublicKnowledgeRead(request) || isPublicFileRead(request)) {
                return true;
            }
            writeFailure(response, "-1", "请先登录");
            return false;
        }

        Claims claims;
        try {
            claims = jwtUtil.parseToken(token);
        } catch (RuntimeException e) {
            writeFailure(response, "-1", "登录状态已过期，请重新登录");
            return false;
        }

        Long userId = claims.get("userId", Long.class);
        if (userId == null) {
            writeFailure(response, "-1", "登录状态已过期，请重新登录");
            return false;
        }

        String sessionUserId;
        User user;
        try {
            sessionUserId = redisTemplate.opsForValue().get("token:" + token);
            if (!String.valueOf(userId).equals(sessionUserId)) {
                writeFailure(response, "-1", "登录状态已失效，请重新登录");
                return false;
            }
            user = userMapper.selectById(userId);
        } catch (DataAccessException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            writeFailure(response, "503", "认证服务暂不可用，请稍后重试");
            return false;
        }

        if (user == null || !Integer.valueOf(1).equals(user.getStatus())
                || user.getUserType() == null
                || !Set.of(1, 2).contains(user.getUserType())) {
            writeFailure(response, "-1", "账号不可用，请重新登录");
            return false;
        }

        request.setAttribute("userId", user.getId());
        request.setAttribute("username", user.getUsername());
        request.setAttribute("userType", user.getUserType());
        return true;
    }

    private boolean isPublicKnowledgeRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_KNOWLEDGE_READS.contains(path)
                || path.matches("/knowledge/article/\\d+");
    }

    private boolean isPublicFileRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/file/uploads/");
    }

    private void writeFailure(HttpServletResponse response, String code, String message) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"msg\":\"" + message + "\"}");
    }
}
