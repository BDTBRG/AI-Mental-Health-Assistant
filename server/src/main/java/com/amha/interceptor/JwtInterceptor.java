package com.amha.interceptor;

import com.amha.config.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_KNOWLEDGE_READS = Set.of(
            "/knowledge/category/tree",
            "/knowledge/article/page"
    );

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("token");
        if (token == null || token.isEmpty()) {
            if (isPublicKnowledgeRead(request)) {
                return true;
            }
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"-1\",\"msg\":\"请先登录\"}");
            return false;
        }
        try {
            Claims claims = jwtUtil.parseToken(token);
            request.setAttribute("userId", claims.get("userId", Long.class));
            request.setAttribute("username", claims.get("username", String.class));
            request.setAttribute("userType", claims.get("userType", Integer.class));
            return true;
        } catch (Exception e) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":\"-1\",\"msg\":\"登录状态已过期，请重新登录\"}");
            return false;
        }
    }

    private boolean isPublicKnowledgeRead(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_KNOWLEDGE_READS.contains(path)
                || path.matches("/knowledge/article/\\d+");
    }
}
