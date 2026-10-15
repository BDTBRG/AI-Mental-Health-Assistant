package com.amha.service.impl;

import com.amha.common.BusinessException;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @Transactional
    public TurnStart beginTurn(Long sessionId, Long userId, String requestId,
                               String requestHash, String userMessage) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "无权访问该会话");
        }

        ConsultationChatTurn existing = turnMapper.selectBySessionAndRequestForUpdate(sessionId, requestId);
        if (existing != null) {
            if (!requestHash.equals(existing.getRequestHash())) {
                throw new BusinessException("409", "clientRequestId 已用于不同消息");
            }
            if ("PENDING".equals(existing.getStatus())) {
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

        ConsultationChatTurn activeTurn = turnMapper.selectActiveBySessionForUpdate(sessionId);
        if (activeTurn != null) {
            LocalDateTime now = LocalDateTime.now();
            if (activeTurn.getUpdatedAt() == null
                    || activeTurn.getUpdatedAt().isAfter(now.minusMinutes(5))) {
                throw new BusinessException("409", "上一条回复仍在生成，请稍后重试");
            }
            Integer maxSequence = eventMapper.selectMaxSequence(
                    sessionId, activeTurn.getClientRequestId());
            activeTurn.setStatus("FAILED");
            activeTurn.setUpdatedAt(now);
            turnMapper.updateById(activeTurn);
            insertEvent(sessionId, activeTurn.getClientRequestId(),
                    (maxSequence == null ? -1 : maxSequence) + 1, "error",
                    "{\"code\":\"500\",\"message\":\"对话服务已中断，请重新发送消息\"}");
        }

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

        session.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + 1);
        session.setLastMessageContent(userMessage);
        session.setLastMessageTime(now);
        sessionMapper.updateById(session);
        return new TurnStart(true, turn.getGenerationToken(), turn.getId(), turn.getGenerationEpoch());
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
                session.setLastMessageContent(answer);
                session.setLastMessageTime(now);
                if (session.getStartedAt() != null) {
                    session.setDurationMinutes((int) Duration.between(session.getStartedAt(), now).toMinutes());
                }
                sessionMapper.updateById(session);
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
        ConsultationChatTurn turn = findTurn(sessionId, requestId);
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

    private ConsultationChatTurn findTurn(Long sessionId, String requestId) {
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
}
