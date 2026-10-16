package com.amha.service;

import com.amha.common.PageResult;
import com.amha.dto.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

public interface ChatService {
    StartSessionResponse startSession(StartSessionRequest request, Long userId);
    StartSessionResponse recoverSessionStart(String clientRequestId, Long userId);
    ChatTurnRecoveryResponse recoverChatTurn(Long sessionId, String clientRequestId, Long userId);
    PageResult<SessionVO> getSessionPage(SessionPageQuery query, Long userId);
    void deleteSession(Long sessionId, Long userId);
    List<ConsultationMessageVO> getSessionMessages(Long sessionId, Long userId, Integer userType);
    SseEmitter streamChat(ChatStreamRequest request, Long userId, Long lastEventId);
    Map<String, Object> getSessionEmotion(Long sessionId, Long userId, Integer userType);
    void updateReviewConsent(Long sessionId, Long userId, Boolean allowed);
    void requestReview(Long sessionId, Long userId);
    void updateReviewState(Long sessionId, Long actorUserId, Integer actorUserType,
                           ReviewStateRequest request);
}
