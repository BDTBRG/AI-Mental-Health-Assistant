package com.amha.service.impl;

import com.amha.common.BusinessException;
import com.amha.common.ChatContextPreparationException;
import com.amha.dto.ChatTurnRecoveryResponse;
import com.amha.dto.ChatContextSnapshot;
import com.amha.entity.ConsultationChatTurn;
import com.amha.entity.ConsultationMessage;
import com.amha.entity.ConsultationSession;
import com.amha.entity.ConsultationStreamEvent;
import com.amha.mapper.ConsultationChatTurnMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.amha.mapper.ConsultationSessionMapper;
import com.amha.mapper.ConsultationStreamEventMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ChatTurnStore {

    public record TurnStart(
            boolean shouldGenerate,
            String generationToken,
            Long turnSequence,
            long generationEpoch
    ) {}

    private final ConsultationChatTurnMapper turnMapper;
    private final ConsultationStreamEventMapper eventMapper;
    private final ConsultationMessageMapper messageMapper;
    private final ConsultationSessionMapper sessionMapper;
    private final ContextSummaryOutboxService contextSummaryOutboxService;

    @Transactional
    public TurnStart beginTurn(Long sessionId, Long userId, String requestId,
                               String requestHash, String userMessage) {
        return beginTurn(sessionId, userId, requestId, requestHash, userMessage, null);
    }

    @Transactional
    public TurnStart beginTurn(Long sessionId, Long userId, String requestId,
                               String requestHash, String userMessage,
                               ChatContextSnapshot preparedSnapshot) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "无权访问该会话");
        }
        if (preparedSnapshot != null) {
            Long latestMessageId = messageMapper.selectMaxMessageIdForOwner(sessionId, userId);
            if (!Objects.equals(latestMessageId, preparedSnapshot.observedLatestMessageId())) {
                throw new ChatContextPreparationException("CONTEXT_SNAPSHOT_STALE",
                        "会话内容已更新，请重试本条消息", requestId, true, 1L);
            }
        }

        ConsultationChatTurn existing = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new BusinessException("409", "clientRequestId 已用于不同消息");
            }
            if ("CONTEXT_SUPERSEDED".equals(existing.getStatus())) {
                throw contextSuperseded(requestId);
            }
            if ("PENDING".equals(existing.getStatus()) || "WAITING_CONTEXT".equals(existing.getStatus())
                    || "CONTEXT_UNAVAILABLE".equals(existing.getStatus())) {
                expireOrRejectOtherActiveTurn(sessionId, requestId);
                supersedeOtherUnavailableTurns(sessionId, requestId);
                String generationToken = UUID.randomUUID().toString();
                long generationEpoch = nextGenerationEpoch(existing);
                existing.setStatus("RUNNING");
                existing.setGenerationToken(generationToken);
                existing.setGenerationEpoch(generationEpoch);
                existing.setUpdatedAt(LocalDateTime.now());
                turnMapper.updateById(existing);
                return new TurnStart(true, generationToken, existing.getId(), generationEpoch);
            }
            if ("RUNNING".equals(existing.getStatus()) && existing.getUpdatedAt() != null
                    && !existing.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(5))) {
                expireOrRejectOtherActiveTurn(sessionId, requestId);
                supersedeOtherUnavailableTurns(sessionId, requestId);
                Integer maxSequence = eventMapper.selectMaxSequence(sessionId, requestId);
                int resetSequence = (maxSequence == null ? -1 : maxSequence) + 1;
                insertEvent(sessionId, requestId, resetSequence, "reset",
                        "{\"code\":\"200\",\"data\":{\"reset\":true}}");
                String generationToken = UUID.randomUUID().toString();
                long generationEpoch = nextGenerationEpoch(existing);
                existing.setGenerationToken(generationToken);
                existing.setGenerationEpoch(generationEpoch);
                existing.setUpdatedAt(LocalDateTime.now());
                turnMapper.updateById(existing);
                return new TurnStart(true, generationToken, existing.getId(), generationEpoch);
            }
            return new TurnStart(false, existing.getGenerationToken(),
                    existing.getId(), existing.getGenerationEpoch() == null ? 0L : existing.getGenerationEpoch());
        }

        supersedeOtherUnavailableTurns(sessionId, requestId);
        expireOrRejectOtherActiveTurn(sessionId, requestId);

        LocalDateTime now = LocalDateTime.now();
        ConsultationChatTurn turn = new ConsultationChatTurn();
        turn.setSessionId(sessionId);
        turn.setClientRequestId(requestId);
        turn.setRequestHash(requestHash);
        turn.setStatus("RUNNING");
        turn.setGenerationToken(UUID.randomUUID().toString());
        turn.setGenerationEpoch(1L);
        turn.setCreatedAt(now);
        turn.setUpdatedAt(now);
        turnMapper.insert(turn);

        ConsultationMessage userMsg = new ConsultationMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setSenderType(1);
        userMsg.setContent(userMessage);
        messageMapper.insert(userMsg);
        turn.setUserMessageId(userMsg.getId());
        turnMapper.updateById(turn);

        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setLastMessageContent(messagePreview(userMessage));
        session.setLastMessageTime(now);
        sessionMapper.updateById(session);
        return new TurnStart(true, turn.getGenerationToken(), turn.getId(), turn.getGenerationEpoch());
    }

    @Transactional
    public void waitForContext(Long sessionId, Long userId, String requestId,
                               String requestHash, String userMessage, Long targetThroughMessageId) {
        if (requestId == null || requestId.isBlank() || requestHash == null || userMessage == null
                || (targetThroughMessageId != null && targetThroughMessageId <= 0)) {
            throw new BusinessException("400", "待整理对话参数无效");
        }
        ConsultationSession session = lockOwnedSession(sessionId, userId);
        ConsultationChatTurn existing = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            if ("CONTEXT_SUPERSEDED".equals(existing.getStatus())) {
                throw contextSuperseded(requestId);
            }
            if ("WAITING_CONTEXT".equals(existing.getStatus())) {
                enqueueSummaryTarget(sessionId, userId, targetThroughMessageId);
                return;
            }
            if ("PENDING".equals(existing.getStatus()) || "CONTEXT_UNAVAILABLE".equals(existing.getStatus())
                    || isStaleRunning(existing)) {
                existing.setStatus("WAITING_CONTEXT");
                existing.setGenerationToken(UUID.randomUUID().toString());
                existing.setUpdatedAt(LocalDateTime.now());
                turnMapper.updateById(existing);
                enqueueSummaryTarget(sessionId, userId, targetThroughMessageId);
                return;
            }
            throw new BusinessException("409", "当前对话轮次已离开待整理状态");
        }

        supersedeOtherUnavailableTurns(sessionId, requestId);
        expireOrRejectOtherActiveTurn(sessionId, requestId);
        LocalDateTime now = LocalDateTime.now();
        ConsultationChatTurn turn = new ConsultationChatTurn();
        turn.setSessionId(sessionId);
        turn.setClientRequestId(requestId);
        turn.setRequestHash(requestHash);
        turn.setStatus("WAITING_CONTEXT");
        turn.setGenerationToken(UUID.randomUUID().toString());
        turn.setGenerationEpoch(0L);
        turn.setCreatedAt(now);
        turn.setUpdatedAt(now);
        turnMapper.insert(turn);

        turn.setUserMessageId(persistUserMessage(session, userMessage, now));
        turnMapper.updateById(turn);
        enqueueSummaryTarget(sessionId, userId, targetThroughMessageId);
    }

    @Transactional
    public void rejectContextTurn(Long sessionId, Long userId, String requestId,
                                  String requestHash, String userMessage) {
        if (requestId == null || requestId.isBlank() || requestHash == null || userMessage == null) {
            throw new BusinessException("400", "对话超限记录参数无效");
        }
        ConsultationSession session = lockOwnedSession(sessionId, userId);
        ConsultationChatTurn existing = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (existing != null) {
            requireSameRequest(existing, requestHash);
            if ("CONTEXT_SUPERSEDED".equals(existing.getStatus())) {
                throw contextSuperseded(requestId);
            }
            if ("CONTEXT_REJECTED".equals(existing.getStatus())) return;
            if ("WAITING_CONTEXT".equals(existing.getStatus())
                    || "CONTEXT_UNAVAILABLE".equals(existing.getStatus())
                    || "PENDING".equals(existing.getStatus())
                    || isStaleRunning(existing)) {
                existing.setStatus("CONTEXT_REJECTED");
                existing.setGenerationToken(UUID.randomUUID().toString());
                existing.setUpdatedAt(LocalDateTime.now());
                turnMapper.updateById(existing);
                return;
            }
            throw new BusinessException("409", "当前对话轮次已不能标记为上下文超限");
        }

        supersedeOtherUnavailableTurns(sessionId, requestId);
        expireOrRejectOtherActiveTurn(sessionId, requestId);
        LocalDateTime now = LocalDateTime.now();
        ConsultationChatTurn turn = new ConsultationChatTurn();
        turn.setSessionId(sessionId);
        turn.setClientRequestId(requestId);
        turn.setRequestHash(requestHash);
        turn.setStatus("CONTEXT_REJECTED");
        turn.setGenerationToken(UUID.randomUUID().toString());
        turn.setGenerationEpoch(0L);
        turn.setCreatedAt(now);
        turn.setUpdatedAt(now);
        turnMapper.insert(turn);
        turn.setUserMessageId(persistUserMessage(session, userMessage, now));
        turnMapper.updateById(turn);
    }

    public ConsultationChatTurn findTurn(Long sessionId, String requestId) {
        return turnMapper.selectBySessionAndRequest(sessionId, requestId);
    }

    @Transactional(readOnly = true)
    public ChatTurnRecoveryResponse recoverTurn(Long sessionId, String requestId, Long userId) {
        if (sessionId == null || userId == null || requestId == null || requestId.isBlank()
                || requestId.length() > 64) {
            throw new BusinessException("400", "恢复请求参数无效");
        }
        ConsultationSession session = sessionMapper.selectById(sessionId);
        if (session == null) throw new BusinessException("404", "会话不存在");
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException("403", "无权访问该会话");
        }

        ConsultationChatTurn turn = turnMapper.selectBySessionAndRequest(sessionId, requestId);
        if (turn == null) return null;
        Long messageId = turn.getUserMessageId();
        if (messageId == null) {
            return new ChatTurnRecoveryResponse(turn.getClientRequestId(), turn.getStatus(),
                    null, null, false);
        }
        ConsultationMessage message = messageMapper.selectUserMessageByIdForOwner(messageId, sessionId, userId);
        if (message == null || message.getContent() == null
                || !sha256(message.getContent()).equals(turn.getRequestHash())) {
            return new ChatTurnRecoveryResponse(turn.getClientRequestId(), turn.getStatus(),
                    null, null, false);
        }

        boolean recoverable = switch (turn.getStatus()) {
            case "PENDING", "WAITING_CONTEXT", "CONTEXT_UNAVAILABLE", "RUNNING" -> true;
            default -> false;
        };
        if (!recoverable) {
            return new ChatTurnRecoveryResponse(turn.getClientRequestId(), turn.getStatus(),
                    null, null, false);
        }
        return new ChatTurnRecoveryResponse(turn.getClientRequestId(), turn.getStatus(),
                message.getId(), message.getContent(), recoverable);
    }

    @Transactional
    public boolean markContextUnavailable(Long sessionId, Long userId, String requestId, String requestHash,
                                          String userMessage, boolean persistMissingRequest) {
        if (sessionId == null || userId == null || requestId == null || requestId.isBlank()
                || requestHash == null || userMessage == null) {
            throw new BusinessException("400", "上下文不可用状态参数无效");
        }
        ConsultationSession session = lockOwnedSession(sessionId, userId);
        ConsultationChatTurn existing = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (existing == null) {
            if (!persistMissingRequest) return false;
            supersedeOtherUnavailableTurns(sessionId, requestId);
            // Keep the original request durable even if another turn is active; retry still checks that turn.
            LocalDateTime now = LocalDateTime.now();
            ConsultationChatTurn turn = new ConsultationChatTurn();
            turn.setSessionId(sessionId);
            turn.setClientRequestId(requestId);
            turn.setRequestHash(requestHash);
            turn.setStatus("CONTEXT_UNAVAILABLE");
            turn.setGenerationToken(UUID.randomUUID().toString());
            turn.setGenerationEpoch(0L);
            turn.setCreatedAt(now);
            turn.setUpdatedAt(now);
            turnMapper.insert(turn);
            turn.setUserMessageId(persistUserMessage(session, userMessage, now));
            turnMapper.updateById(turn);
            return false;
        }
        requireSameRequest(existing, requestHash);
        if ("CONTEXT_SUPERSEDED".equals(existing.getStatus())) return true;
        if ("CONTEXT_UNAVAILABLE".equals(existing.getStatus())) return false;
        if (!"WAITING_CONTEXT".equals(existing.getStatus()) && !"PENDING".equals(existing.getStatus())
                && !isStaleRunning(existing)) return false;
        existing.setStatus("CONTEXT_UNAVAILABLE");
        existing.setGenerationToken(UUID.randomUUID().toString());
        existing.setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(existing);
        return false;
    }

    @Transactional
    public void appendChunk(Long sessionId, String requestId, String generationToken,
                            int sequence, String payload) {
        ConsultationChatTurn turn = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (!isCurrentGeneration(turn, generationToken)) {
            return;
        }
        insertEvent(sessionId, requestId, sequence, "message", payload);
        turn.setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
    }

    @Transactional
    public void completeTurn(Long sessionId, String requestId, String generationToken, String answer,
                             int sequence, String donePayload) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) return;
        ConsultationChatTurn turn = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (!isCurrentGeneration(turn, generationToken)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (answer != null && !answer.isEmpty()) {
            ConsultationMessage aiMsg = new ConsultationMessage();
            aiMsg.setSessionId(sessionId);
            aiMsg.setSenderType(2);
            aiMsg.setContent(answer);
            messageMapper.insert(aiMsg);

            if (session != null) {
                session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
                session.setLastMessageContent(messagePreview(answer));
                session.setLastMessageTime(now);
                if (session.getStartedAt() != null) {
                    session.setDurationMinutes((int) Duration.between(session.getStartedAt(), now).toMinutes());
                }
                sessionMapper.updateById(session);
            }
        }

        if (session != null) {
            Long summaryTarget = messageMapper.selectSummaryTargetBeforeRecentMessages(
                    sessionId, session.getUserId(), 12);
            if (summaryTarget != null && summaryTarget > 0) {
                contextSummaryOutboxService.enqueue(sessionId, session.getUserId(), summaryTarget);
            }
        }

        turn.setStatus("COMPLETED");
        turn.setUpdatedAt(now);
        turnMapper.updateById(turn);
        insertEvent(sessionId, requestId, sequence, "done", donePayload);
    }

    @Transactional
    public void failTurn(Long sessionId, String requestId, String generationToken,
                         int sequence, String errorPayload) {
        sessionMapper.selectByIdForUpdate(sessionId);
        ConsultationChatTurn turn = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (!isCurrentGeneration(turn, generationToken)) {
            return;
        }
        turn.setStatus("FAILED");
        turn.setUpdatedAt(LocalDateTime.now());
        turnMapper.updateById(turn);
        insertEvent(sessionId, requestId, sequence, "error", errorPayload);
    }

    @Transactional
    public void failIfStale(Long sessionId, String requestId) {
        ConsultationChatTurn turn = findTurnForFailure(sessionId, requestId);
        if (turn == null || !"RUNNING".equals(turn.getStatus())
                || turn.getUpdatedAt() == null
                || turn.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(5))) {
            return;
        }
        Integer maxSequence = eventMapper.selectMaxSequence(sessionId, requestId);
        failTurn(sessionId, requestId, turn.getGenerationToken(), (maxSequence == null ? -1 : maxSequence) + 1,
                "{\"code\":\"500\",\"message\":\"对话服务已中断，请重新发送消息\"}");
    }

    public int nextSequence(Long sessionId, String requestId) {
        Integer maxSequence = eventMapper.selectMaxSequence(sessionId, requestId);
        return (maxSequence == null ? -1 : maxSequence) + 1;
    }

    private ConsultationChatTurn findTurnForFailure(Long sessionId, String requestId) {
        return turnMapper.selectOne(new LambdaQueryWrapper<ConsultationChatTurn>()
                .eq(ConsultationChatTurn::getSessionId, sessionId)
                .eq(ConsultationChatTurn::getClientRequestId, requestId)
                .last("LIMIT 1"));
    }

    private boolean isCurrentGeneration(ConsultationChatTurn turn, String generationToken) {
        return turn != null && "RUNNING".equals(turn.getStatus())
                && generationToken != null && generationToken.equals(turn.getGenerationToken());
    }

    private long nextGenerationEpoch(ConsultationChatTurn turn) {
        return Math.addExact(turn.getGenerationEpoch() == null ? 0L : turn.getGenerationEpoch(), 1L);
    }

    private void insertEvent(Long sessionId, String requestId, int sequence,
                             String eventType, String payload) {
        ConsultationStreamEvent event = new ConsultationStreamEvent();
        event.setSessionId(sessionId);
        event.setClientRequestId(requestId);
        event.setSequence(sequence);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setCreatedAt(LocalDateTime.now());
        eventMapper.insert(event);
    }

    private ConsultationSession lockOwnedSession(Long sessionId, Long userId) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) throw new BusinessException("会话不存在");
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "无权访问该会话");
        }
        return session;
    }

    private void requireSameRequest(ConsultationChatTurn turn, String requestHash) {
        if (!requestHash.equals(turn.getRequestHash())) {
            throw new BusinessException("409", "clientRequestId 已用于不同消息");
        }
    }

    private void enqueueSummaryTarget(Long sessionId, Long userId, Long targetThroughMessageId) {
        if (targetThroughMessageId != null && targetThroughMessageId > 0) {
            contextSummaryOutboxService.enqueue(sessionId, userId, targetThroughMessageId);
        }
    }

    private boolean isStaleRunning(ConsultationChatTurn turn) {
        return "RUNNING".equals(turn.getStatus()) && (turn.getUpdatedAt() == null
                || !turn.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(5)));
    }

    private Long persistUserMessage(ConsultationSession session, String userMessage, LocalDateTime now) {
        ConsultationMessage userMsg = new ConsultationMessage();
        userMsg.setSessionId(session.getId());
        userMsg.setSenderType(1);
        userMsg.setContent(userMessage);
        messageMapper.insert(userMsg);

        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setLastMessageContent(messagePreview(userMessage));
        session.setLastMessageTime(now);
        sessionMapper.updateById(session);
        return userMsg.getId();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash chat recovery message", error);
        }
    }

    private String messagePreview(String message) {
        if (message == null || message.codePointCount(0, message.length()) <= 1000) return message;
        int endIndex = message.offsetByCodePoints(0, 999);
        return message.substring(0, endIndex) + "…";
    }

    private void supersedeOtherUnavailableTurns(Long sessionId, String requestId) {
        turnMapper.supersedeUnavailableBySessionExceptRequest(sessionId, requestId);
    }

    private static ChatContextPreparationException contextSuperseded(String requestId) {
        return new ChatContextPreparationException("CONTEXT_REQUEST_SUPERSEDED",
                "该请求已被后续对话替代，请从最近一条消息继续", requestId, false, 0L);
    }

    private void expireOrRejectOtherActiveTurn(Long sessionId, String requestId) {
        List<ConsultationChatTurn> activeTurns = turnMapper.selectOtherActiveBySessionForUpdate(sessionId, requestId);
        if (activeTurns == null || activeTurns.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now();

        // Check every active turn before expiring any of them. A stale earlier row must not
        // hide a newer pending generation or a turn that is still waiting for its summary.
        for (ConsultationChatTurn activeTurn : activeTurns) {
            if ("WAITING_CONTEXT".equals(activeTurn.getStatus())) {
                throw new BusinessException("409", "上一条消息仍在整理上下文，请使用原 clientRequestId 重试");
            }
            if (activeTurn.getUpdatedAt() == null || activeTurn.getUpdatedAt().isAfter(now.minusMinutes(5))) {
                throw new BusinessException("409", "上一条回复仍在生成，请稍后重试");
            }
        }

        for (ConsultationChatTurn activeTurn : activeTurns) {
            Integer maxSequence = eventMapper.selectMaxSequence(sessionId, activeTurn.getClientRequestId());
            activeTurn.setStatus("FAILED");
            activeTurn.setUpdatedAt(now);
            turnMapper.updateById(activeTurn);
            insertEvent(sessionId, activeTurn.getClientRequestId(),
                    (maxSequence == null ? -1 : maxSequence) + 1, "error",
                    "{\"code\":\"500\",\"message\":\"对话服务已中断，请重新发送消息\"}");
        }
    }
}
