package com.amha.controller;

import com.amha.common.BusinessException;
import com.amha.common.Result;
import com.amha.service.impl.ContextSummaryOutboxService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/context-summary")
@RequiredArgsConstructor
public class ContextSummaryAdminController {
    private final ContextSummaryOutboxService outboxService;

    @PostMapping("/{sessionId}/retry")
    public Result<Map<String, Boolean>> retryDead(@PathVariable Long sessionId, HttpServletRequest request) {
        Long actorUserId = requireAdmin(request);
        boolean retried = outboxService.retryDead(sessionId, actorUserId);
        return Result.success(Map.of("retried", retried));
    }

    private Long requireAdmin(HttpServletRequest request) {
        Integer userType = (Integer) request.getAttribute("userType");
        Long actorUserId = (Long) request.getAttribute("userId");
        if (!Integer.valueOf(2).equals(userType) || actorUserId == null) {
            throw new BusinessException("403", "仅管理员可重放摘要任务");
        }
        return actorUserId;
    }
}
