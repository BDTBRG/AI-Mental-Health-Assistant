package com.amha.interceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

@Component
public class AdminAuthorizationInterceptor implements HandlerInterceptor {
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        if (!requiresAdmin(request)) return true;
        Integer userType = (Integer) request.getAttribute("userType");
        if (Integer.valueOf(2).equals(userType)) return true;

        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"403\",\"msg\":\"仅管理员可访问该接口\"}");
        return false;
    }

    private boolean requiresAdmin(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.startsWith("/data-analytics/")
                || path.startsWith("/emotion-diary/admin/")
                || path.startsWith("/psychological-chat/admin/")) {
            return true;
        }
        return path.startsWith("/knowledge/") && WRITE_METHODS.contains(request.getMethod().toUpperCase());
    }
}
