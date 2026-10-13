package com.amha.controller;

import com.amha.common.PageResult;
import com.amha.common.Result;
import com.amha.dto.*;
import com.amha.service.ChatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/psychological-chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/session/start")
    public Result<StartSessionResponse> startSession(@RequestBody StartSessionRequest request,
                                                      HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(chatService.startSession(request, userId));
    }

    @GetMapping("/sessions")
    public Result<PageResult<SessionVO>> getSessions(SessionPageQuery query,
                                                      HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        Integer userType = (Integer) httpRequest.getAttribute("userType");
        // 管理员查看所有会话，普通用户只看自己的
        if (userType == 2) {
            // 管理员端，不限制userId
            return Result.success(chatService.getSessionPage(query, null));
        }
        return Result.success(chatService.getSessionPage(query, userId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId,
                                       HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        chatService.deleteSession(sessionId, userId);
        return Result.success();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessageVO>> getSessionMessages(@PathVariable Long sessionId,
                                                                  HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        Integer userType = (Integer) httpRequest.getAttribute("userType");
        return Result.success(chatService.getSessionMessages(sessionId, userId, userType));
    }

    @PutMapping("/sessions/{sessionId}/review-consent")
    public Result<Void> updateReviewConsent(@PathVariable Long sessionId,
                                             @Valid @RequestBody ReviewConsentRequest request,
                                             HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        chatService.updateReviewConsent(sessionId, userId, request.getAllowed());
        return Result.success();
    }

    @PostMapping("/sessions/{sessionId}/review-request")
    public Result<Void> requestReview(@PathVariable Long sessionId,
                                      HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        chatService.requestReview(sessionId, userId);
        return Result.success();
    }

    @PutMapping("/admin/sessions/{sessionId}/review-state")
    public Result<Void> updateReviewState(@PathVariable Long sessionId,
                                          @Valid @RequestBody ReviewStateRequest request,
                                          HttpServletRequest httpRequest) {
        Long actorUserId = (Long) httpRequest.getAttribute("userId");
        Integer actorUserType = (Integer) httpRequest.getAttribute("userType");
        chatService.updateReviewState(sessionId, actorUserId, actorUserType, request);
        return Result.success();
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatStreamRequest request,
                                  @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                                  HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        long cursor = 0L;
        if (lastEventId != null && !lastEventId.isBlank()) {
            try {
                cursor = Math.max(0L, Long.parseLong(lastEventId));
            } catch (NumberFormatException ignored) {
                cursor = 0L;
            }
        }
        return chatService.streamChat(request, userId, cursor);
    }

    @GetMapping("/session/{sessionId}/emotion")
    public Result<Map<String, Object>> getSessionEmotion(@PathVariable String sessionId,
                                                          HttpServletRequest httpRequest) {
        Long sid = Long.parseLong(sessionId.replace("session_", ""));
        Long userId = (Long) httpRequest.getAttribute("userId");
        Integer userType = (Integer) httpRequest.getAttribute("userType");
        return Result.success(chatService.getSessionEmotion(sid, userId, userType));
    }
}
