package com.amha.controller;

import com.amha.common.Result;
import com.amha.dto.AgentMemoryConsentRequest;
import com.amha.dto.AgentMemoryRequest;
import com.amha.dto.AgentMemoryVO;
import com.amha.service.AgentMemoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/psychological-chat/memories")
@RequiredArgsConstructor
public class AgentMemoryController {
    private final AgentMemoryService memoryService;

    @GetMapping("/consent")
    public Result<Map<String, Boolean>> getConsent(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(Map.of("enabled", memoryService.isPersonalizationEnabled(userId)));
    }

    @PutMapping("/consent")
    public Result<Void> setConsent(@Valid @RequestBody AgentMemoryConsentRequest body,
                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        memoryService.setPersonalizationEnabled(userId, body.getAllowed());
        return Result.success();
    }

    @GetMapping
    public Result<List<AgentMemoryVO>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(memoryService.list(userId));
    }

    @PostMapping
    public Result<AgentMemoryVO> create(@Valid @RequestBody AgentMemoryRequest body,
                                        HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(memoryService.create(userId, body));
    }

    @PutMapping("/{memoryId}")
    public Result<Void> update(@PathVariable Long memoryId,
                               @Valid @RequestBody AgentMemoryRequest body,
                               HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        memoryService.update(userId, memoryId, body);
        return Result.success();
    }

    @DeleteMapping("/{memoryId}")
    public Result<Void> delete(@PathVariable Long memoryId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        memoryService.delete(userId, memoryId);
        return Result.success();
    }

    @DeleteMapping
    public Result<Void> clearAndDisable(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        memoryService.clearAndDisable(userId);
        return Result.success();
    }
}
