package com.amha.service.impl;

import com.amha.agent.ChatAgent;
import com.amha.common.BusinessException;
import com.amha.common.ChatContextPreparationException;
import com.amha.common.PageResult;
import com.amha.common.SessionIdParser;
import com.amha.config.ChatContextSummaryProperties;
import com.amha.dto.*;
import com.amha.entity.ConsultationMessage;
import com.amha.entity.ConsultationChatTurn;
import com.amha.entity.ConsultationReviewAudit;
import com.amha.entity.ConsultationSession;
import com.amha.entity.ConsultationStreamEvent;
import com.amha.entity.EmotionDiary;
import com.amha.entity.AgentMemory;
import com.amha.entity.AgentMemorySettings;
import com.amha.entity.User;
import com.amha.mapper.ConsultationChatTurnMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.amha.mapper.ConsultationReviewAuditMapper;
import com.amha.mapper.ConsultationSessionMapper;
import com.amha.mapper.ConsultationStreamEventMapper;
import com.amha.mapper.EmotionDiaryMapper;
import com.amha.mapper.AgentMemoryMapper;
import com.amha.mapper.AgentMemorySettingsMapper;
import com.amha.mapper.UserMapper;
import com.amha.service.ChatService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ConsultationSessionMapper sessionMapper;
    private final ConsultationMessageMapper messageMapper;
    private final ConsultationReviewAuditMapper reviewAuditMapper;
    private final ConsultationChatTurnMapper chatTurnMapper;
    private final ConsultationStreamEventMapper streamEventMapper;
    private final AgentCheckpointPurgeOutboxService checkpointPurgeOutboxService;
    private final ContextSummaryOutboxService contextSummaryOutboxService;
    private final ChatTurnStore chatTurnStore;
    private final ChatContextAssembler chatContextAssembler;
    private final ChatContextSummaryProperties chatContextSummaryProperties;
    private final EmotionDiaryMapper diaryMapper;
    private final AgentMemoryMapper agentMemoryMapper;
    private final AgentMemorySettingsMapper agentMemorySettingsMapper;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Qualifier("realChatAgent")
    private final ChatAgent chatAgent;

    @Override
    @Transactional
    public StartSessionResponse startSession(StartSessionRequest request, Long userId) {
        if (request == null || userId == null) {
            throw new BusinessException("400", "会话参数无效");
        }
        String startRequestId = request.getClientRequestId();
        boolean persistFirstTurn = startRequestId != null;
        if (persistFirstTurn && startRequestId.isBlank()) {
            throw new BusinessException("400", "clientRequestId 不能为空");
        }
        String initialMessage = request.getInitialMessage();
        if (persistFirstTurn && (initialMessage == null || initialMessage.isBlank()
                || initialMessage.length() > 500)) {
            throw new BusinessException("400", "首条消息不能为空且不能超过500字");
        }

        String startRequestHash = persistFirstTurn ? startRequestHash(request) : null;
        if (persistFirstTurn) {
            ConsultationSession existing = sessionMapper.selectByUserAndStartRequestId(userId, startRequestId);
            if (existing != null) {
                return existingStartResponse(existing, request, startRequestHash);
            }
        }

        LocalDateTime now = LocalDateTime.now();
        ConsultationSession session = new ConsultationSession();
        session.setUserId(userId);
        session.setSessionTitle(request.getSessionTitle());
        session.setStatus("ACTIVE");
        session.setStartedAt(now);
        session.setMessageCount(persistFirstTurn ? 1 : 0);
        if (persistFirstTurn) {
            session.setStartRequestId(startRequestId);
            session.setStartRequestHash(startRequestHash);
            session.setLastMessageContent(initialMessage);
            session.setLastMessageTime(now);
        }
        session.setReviewConsent(Boolean.TRUE.equals(request.getReviewConsent()));
        if (Boolean.TRUE.equals(session.getReviewConsent())) {
            session.setReviewConsentUpdatedAt(now);
        }
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException collision) {
            if (persistFirstTurn) {
                ConsultationSession existing = sessionMapper.selectByUserAndStartRequestIdForUpdate(
                        userId, startRequestId);
                if (existing != null) {
                    return existingStartResponse(existing, request, startRequestHash);
                }
            }
            throw collision;
        }
        if (Boolean.TRUE.equals(session.getReviewConsent())) {
            ConsultationReviewAudit audit = new ConsultationReviewAudit();
            audit.setSessionId(session.getId());
            audit.setActorUserId(userId);
            audit.setAction("CONSENT_GRANTED");
            reviewAuditMapper.insert(audit);
        }

        if (persistFirstTurn) {
            ConsultationMessage firstMessage = new ConsultationMessage();
            firstMessage.setSessionId(session.getId());
            firstMessage.setSenderType(1);
            firstMessage.setContent(initialMessage);
            messageMapper.insert(firstMessage);

            ConsultationChatTurn firstTurn = new ConsultationChatTurn();
            firstTurn.setSessionId(session.getId());
            firstTurn.setClientRequestId(startRequestId);
            firstTurn.setUserMessageId(firstMessage.getId());
            firstTurn.setRequestHash(sha256(initialMessage));
            firstTurn.setStatus("PENDING");
            firstTurn.setGenerationToken(UUID.randomUUID().toString());
            firstTurn.setGenerationEpoch(0L);
            firstTurn.setCreatedAt(now);
            firstTurn.setUpdatedAt(now);
            chatTurnMapper.insert(firstTurn);
        }

        return toStartSessionResponse(session, initialMessage);
    }

    @Override
    public StartSessionResponse recoverSessionStart(String clientRequestId, Long userId) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 64
                || userId == null) {
            throw new BusinessException("400", "恢复请求参数无效");
        }
        ConsultationSession session = sessionMapper.selectByUserAndStartRequestId(userId, clientRequestId);
        if (session == null) {
            return null;
        }
        return toStartSessionResponse(session, null);
    }

    @Override
    public ChatTurnRecoveryResponse recoverChatTurn(Long sessionId, String clientRequestId, Long userId) {
        return chatTurnStore.recoverTurn(sessionId, clientRequestId, userId);
    }

    private StartSessionResponse existingStartResponse(ConsultationSession session,
                                                         StartSessionRequest request,
                                                         String expectedRequestHash) {
        if (!expectedRequestHash.equals(session.getStartRequestHash())) {
            throw new BusinessException("409", "clientRequestId 已用于不同的会话请求");
        }
        ConsultationChatTurn firstTurn = chatTurnMapper.selectBySessionAndRequestForUpdate(
                session.getId(), request.getClientRequestId());
        if (firstTurn == null || !sha256(request.getInitialMessage()).equals(firstTurn.getRequestHash())) {
            throw new BusinessException("409", "首轮会话状态不匹配，请使用新的请求标识");
        }
        return toStartSessionResponse(session, request.getInitialMessage());
    }

    private String startRequestHash(StartSessionRequest request) {
        String message = request.getInitialMessage();
        String title = request.getSessionTitle();
        String reviewConsent = Boolean.TRUE.equals(request.getReviewConsent()) ? "1" : "0";
        return sha256(hashField(message) + hashField(title) + reviewConsent);
    }

    private String hashField(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }

    private StartSessionResponse toStartSessionResponse(ConsultationSession session, String initialMessage) {
        long startTime = session.getStartedAt() == null ? System.currentTimeMillis()
                : session.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return StartSessionResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus())
                .startTime(startTime)
                .initialMessage(initialMessage)
                .messageCount(session.getMessageCount())
                .sessionTitle(session.getSessionTitle())
                .reviewConsent(Boolean.TRUE.equals(session.getReviewConsent()))
                .build();
    }

    @Override
    public PageResult<SessionVO> getSessionPage(SessionPageQuery query, Long userId) {
        LambdaQueryWrapper<ConsultationSession> wrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            wrapper.eq(ConsultationSession::getUserId, userId);
        } else {
            // 只有明确申请复核且尚未撤回授权的会话可以进入人工工作台。
            wrapper.eq(ConsultationSession::getReviewConsent, true);
            String reviewStatus = query.getReviewStatus();
            if (reviewStatus == null || reviewStatus.isBlank()) {
                reviewStatus = "PENDING";
            }
            if (List.of("PENDING", "IN_PROGRESS", "RESOLVED").contains(reviewStatus)) {
                wrapper.eq(ConsultationSession::getReviewStatus, reviewStatus);
            } else {
                wrapper.eq(ConsultationSession::getId, -1L);
            }
        }
        wrapper.orderByDesc(ConsultationSession::getLastMessageTime)
               .orderByDesc(ConsultationSession::getCreatedAt);

        Page<ConsultationSession> page = new Page<>(query.getPageNum(), query.getPageSize());
        IPage<ConsultationSession> result = sessionMapper.selectPage(page, wrapper);

        List<SessionVO> records = result.getRecords().stream().map(session -> {
            User user = userMapper.selectById(session.getUserId());
            return SessionVO.builder()
                    .id(session.getId())
                    .userId(session.getUserId())
                    .userNickname(user != null ? user.getNickname() : "")
                    .sessionTitle(session.getSessionTitle())
                    .startedAt(session.getStartedAt())
                    .durationMinutes(session.getDurationMinutes())
                    .messageCount(session.getMessageCount())
                    .lastMessageContent(session.getLastMessageContent())
                    .lastMessageTime(session.getLastMessageTime())
                    .reviewConsent(session.getReviewConsent())
                    .reviewStatus(session.getReviewStatus())
                    .reviewAssigneeId(session.getReviewAssigneeId())
                    .reviewVersion(session.getReviewVersion())
                    .build();
        }).collect(Collectors.toList());

        return PageResult.of(records, result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Override
    @Transactional
    public void deleteSession(Long sessionId, Long userId) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (userId == null || !session.getUserId().equals(userId)) {
            throw new BusinessException("403", "仅会话所有者可删除该会话");
        }
        Long runningTurns = chatTurnMapper.selectCount(new LambdaQueryWrapper<ConsultationChatTurn>()
                .eq(ConsultationChatTurn::getSessionId, sessionId)
                .eq(ConsultationChatTurn::getStatus, "RUNNING"));
        if (runningTurns != null && runningTurns > 0) {
            throw new BusinessException("409", "会话仍有回复生成中，请稍后删除");
        }
        contextSummaryOutboxService.deleteForSession(sessionId);
        streamEventMapper.delete(new LambdaQueryWrapper<ConsultationStreamEvent>()
                .eq(ConsultationStreamEvent::getSessionId, sessionId));
        checkpointPurgeOutboxService.enqueueSession(sessionId, userId);
        chatTurnMapper.delete(new LambdaQueryWrapper<ConsultationChatTurn>()
                .eq(ConsultationChatTurn::getSessionId, sessionId));
        messageMapper.delete(new LambdaQueryWrapper<ConsultationMessage>()
                .eq(ConsultationMessage::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
    }

    @Override
    public List<ConsultationMessageVO> getSessionMessages(Long sessionId, Long userId, Integer userType) {
        ConsultationSession session = requireReadableSession(sessionId, userId, userType);
        recordReviewRead(session, userId, "READ_MESSAGES");
        List<ConsultationMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ConsultationMessage>()
                        .eq(ConsultationMessage::getSessionId, sessionId)
                        .orderByAsc(ConsultationMessage::getCreatedAt));

        return messages.stream().map(msg -> {
            String content = msg.getContent();
            int contentLength = content != null ? content.length() : 0;
            String preview = content != null && content.length() > 50
                    ? content.substring(0, 50) + "..." : content;

            return ConsultationMessageVO.builder()
                    .id(msg.getId())
                    .sessionId(msg.getSessionId())
                    .senderType(msg.getSenderType())
                    .senderTypeDesc(msg.getSenderType() == 1 ? "用户" : "AI")
                    .messageType(msg.getMessageType())
                    .messageTypeDesc(msg.getMessageType() == 0 ? "文本" : "其他")
                    .content(content)
                    .contentLength(contentLength)
                    .contentPreview(preview)
                    .isError(msg.getIsError() == 1)
                    .createdAt(msg.getCreatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    @Override
    public SseEmitter streamChat(ChatStreamRequest request, Long userId, Long lastEventId) {
        if (request == null) {
            throw new BusinessException("400", "对话请求不能为空");
        }
        Long sessionId = SessionIdParser.parse(request == null ? null : request.getSessionId());
        ConsultationSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "无权访问该会话");
        }

        String requestId = request.getClientRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        if (requestId.length() > 64) {
            throw new BusinessException("400", "clientRequestId 长度不能超过64");
        }
        final String turnRequestId = requestId;
        String userMessage = request.getUserMessage() == null ? "" : request.getUserMessage();
        if (userMessage.isBlank()) {
            throw new BusinessException("400", "对话内容不能为空");
        }
        String requestHash = sha256(userMessage);
        ConsultationChatTurn existingTurn = chatTurnStore.findTurn(sessionId, turnRequestId);
        if (existingTurn != null && !requestHash.equals(existingTurn.getRequestHash())) {
            throw new BusinessException("409", "clientRequestId 已用于不同消息");
        }
        if (userMessage.codePointCount(0, userMessage.length())
                > chatContextSummaryProperties.getMaxSingleMessageChars()) {
            chatTurnStore.markContextUnavailable(sessionId, userId, turnRequestId, requestHash,
                    userMessage, false);
            throw new BusinessException("400", "单条对话内容超过长度上限，请缩短后重试");
        }
        if (existingTurn != null && "CONTEXT_REJECTED".equals(existingTurn.getStatus())) {
            throw new ChatContextPreparationException("CONTEXT_WINDOW_TOO_LARGE",
                    "近期对话内容超过安全处理上限，请新建会话后继续", turnRequestId, false, 0L);
        }
        if (existingTurn != null && "CONTEXT_SUPERSEDED".equals(existingTurn.getStatus())) {
            throw new ChatContextPreparationException("CONTEXT_REQUEST_SUPERSEDED",
                    "该请求已被后续对话替代，请从最近一条消息继续", turnRequestId, false, 0L);
        }

        boolean staleRunningTurn = existingTurn != null && "RUNNING".equals(existingTurn.getStatus())
                && (existingTurn.getUpdatedAt() == null
                || !existingTurn.getUpdatedAt().isAfter(LocalDateTime.now().minusMinutes(5)));
        boolean prepareContext = existingTurn == null || "PENDING".equals(existingTurn.getStatus())
                || "WAITING_CONTEXT".equals(existingTurn.getStatus())
                || "CONTEXT_UNAVAILABLE".equals(existingTurn.getStatus()) || staleRunningTurn;

        ChatTurnStore.TurnStart turnStart;
        ChatContextSnapshot contextSnapshot = null;
        Map<String, Object> preparedAgentContext = null;
        if (prepareContext) {
            preparedAgentContext = new java.util.LinkedHashMap<>(buildAgentContext(userId));
            ChatContextPreparation preparation = chatContextAssembler.prepare(
                    sessionId, userId, userMessage, existingTurn != null,
                    existingTurn == null ? null : existingTurn.getUserMessageId(), preparedAgentContext);
            if (preparation.status() == ChatContextPreparation.Status.WINDOW_TOO_LARGE) {
                chatTurnStore.rejectContextTurn(sessionId, userId, turnRequestId, requestHash, userMessage);
                throw preparation.toException(turnRequestId);
            }
            if (preparation.status() == ChatContextPreparation.Status.SUMMARY_PENDING) {
                chatTurnStore.waitForContext(sessionId, userId, turnRequestId, requestHash,
                        userMessage, preparation.summaryTargetThroughMessageId());
                throw preparation.toException(turnRequestId);
            }
            if (preparation.status() == ChatContextPreparation.Status.SUMMARY_UNAVAILABLE) {
                boolean superseded = chatTurnStore.markContextUnavailable(sessionId, userId, turnRequestId,
                        requestHash, userMessage, true);
                if (superseded) {
                    throw new ChatContextPreparationException("CONTEXT_REQUEST_SUPERSEDED",
                            "该请求已被后续对话替代，请从最近一条消息继续", turnRequestId, false, 0L);
                }
                throw preparation.toException(turnRequestId);
            }
            contextSnapshot = preparation.toSnapshot();
            try {
                turnStart = chatTurnStore.beginTurn(sessionId, userId, turnRequestId, requestHash,
                        userMessage, contextSnapshot);
            } catch (ChatContextPreparationException staleSnapshot) {
                if (!"CONTEXT_SNAPSHOT_STALE".equals(staleSnapshot.getErrorCode())) throw staleSnapshot;
                boolean superseded = chatTurnStore.markContextUnavailable(sessionId, userId, turnRequestId,
                        requestHash, userMessage, true);
                if (superseded) {
                    throw new ChatContextPreparationException("CONTEXT_REQUEST_SUPERSEDED",
                            "该请求已被后续对话替代，请从最近一条消息继续", turnRequestId, false, 0L);
                }
                throw staleSnapshot;
            }
        } else {
            // Existing active or terminal turns attach/replay without waiting for summary work.
            turnStart = chatTurnStore.beginTurn(sessionId, userId, turnRequestId, requestHash, userMessage);
        }

        SseEmitter emitter = new SseEmitter(300000L);
        if (turnStart.shouldGenerate()) {
            if (contextSnapshot == null) {
                throw new IllegalStateException("New Agent generation has no prepared context snapshot");
            }
            List<ConsultationMessage> history = contextSnapshot.history();
            AtomicInteger eventSequence = new AtomicInteger(
                    chatTurnStore.nextSequence(sessionId, turnRequestId));
            StringBuilder fullResponse = new StringBuilder();
            Map<String, Object> agentContext = preparedAgentContext == null
                    ? new java.util.LinkedHashMap<>(buildAgentContext(userId)) : preparedAgentContext;
            agentContext.put("sessionId", sessionId.toString());
            agentContext.put("turnId", turnRequestId);
            agentContext.put("turnSequence", turnStart.turnSequence());
            agentContext.put("generationEpoch", turnStart.generationEpoch());
            agentContext.put("generationToken", turnStart.generationToken());
            agentContext.put("userId", userId.toString());
            Flux<String> response = chatAgent.streamResponse(sessionId, userMessage, history,
                    contextSnapshot.contextSummary(), agentContext);
            if (response == null) {
                response = Flux.error(new IllegalStateException("Agent returned an empty stream"));
            }
            response
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .publishOn(Schedulers.boundedElastic(), 1)
                    .doOnNext(chunk -> {
                        fullResponse.append(chunk);
                        chatTurnStore.appendChunk(sessionId, turnRequestId, turnStart.generationToken(),
                                eventSequence.getAndIncrement(), messagePayload(chunk));
                    })
                    .doOnComplete(() -> chatTurnStore.completeTurn(sessionId, turnRequestId,
                            turnStart.generationToken(),
                            fullResponse.toString(), eventSequence.getAndIncrement(), donePayload()))
                    .doOnError(error -> {
                        log.error("SSE stream error for session {} request {}", sessionId, turnRequestId, error);
                        chatTurnStore.failTurn(sessionId, turnRequestId, turnStart.generationToken(),
                                eventSequence.getAndIncrement(),
                                errorPayload());
                    })
                    .subscribe(ignored -> { }, error -> log.debug("Agent stream terminated for request {}", turnRequestId));
        }

        attachToTurn(sessionId, turnRequestId,
                lastEventId == null ? 0L : Math.max(0L, lastEventId), emitter);
        return emitter;
    }

    private void attachToTurn(Long sessionId, String requestId, Long lastEventId, SseEmitter emitter) {
        AtomicLong cursor = new AtomicLong(lastEventId);
        chatTurnStore.failIfStale(sessionId, requestId);
        try {
            if (sendEventsAndCheckTerminal(sessionId, requestId, cursor, emitter)) {
                emitter.complete();
                return;
            }
        } catch (IOException error) {
            emitter.completeWithError(error);
            return;
        }

        Disposable subscription = Flux.interval(Duration.ofMillis(200), Duration.ofMillis(200))
                .concatMap(tick -> {
                    try {
                        return Mono.just(sendEventsAndCheckTerminal(sessionId, requestId, cursor, emitter));
                    } catch (IOException error) {
                        return Mono.error(error);
                    }
                })
                .takeUntil(Boolean::booleanValue)
                .subscribe(ignored -> { }, error -> emitter.completeWithError(error), emitter::complete);
        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(subscription::dispose);
    }

    private boolean sendEventsAndCheckTerminal(Long sessionId, String requestId,
                                               AtomicLong cursor, SseEmitter emitter) throws IOException {
        List<ConsultationStreamEvent> events = streamEventMapper.selectAfter(
                sessionId, requestId, cursor.get());
        for (ConsultationStreamEvent event : events) {
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(event.getId()))
                    .name(event.getEventType())
                    .data(event.getPayload()));
            cursor.set(event.getId());
        }

        ConsultationChatTurn turn = chatTurnMapper.selectOne(
                new LambdaQueryWrapper<ConsultationChatTurn>()
                        .eq(ConsultationChatTurn::getSessionId, sessionId)
                        .eq(ConsultationChatTurn::getClientRequestId, requestId)
                        .last("LIMIT 1"));
        return turn != null && !"RUNNING".equals(turn.getStatus());
    }

    private String messagePayload(String chunk) {
        return json(Map.of("code", "200", "data", Map.of("content", chunk)));
    }

    private Map<String, Object> buildAgentContext(Long userId) {
        AgentMemorySettings settings = agentMemorySettingsMapper.selectById(userId);
        boolean personalizationEnabled = settings != null
                && Boolean.TRUE.equals(settings.getPersonalizationEnabled());
        List<EmotionDiary> diaries = personalizationEnabled
                ? diaryMapper.selectList(new LambdaQueryWrapper<EmotionDiary>()
                        .eq(EmotionDiary::getUserId, userId)
                        .ge(EmotionDiary::getDiaryDate, LocalDate.now().minusDays(89))
                        .orderByDesc(EmotionDiary::getDiaryDate)
                        .last("LIMIT 30"))
                : List.of();
        List<Map<String, Object>> history = diaries.stream().map(diary -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("diaryDate", diary.getDiaryDate() == null ? null : diary.getDiaryDate().toString());
            item.put("dominantEmotion", diary.getDominantEmotion());
            item.put("moodScore", diary.getMoodScore());
            item.put("sleepQuality", diary.getSleepQuality());
            item.put("stressLevel", diary.getStressLevel());
            return item;
        }).collect(Collectors.toList());
        List<Map<String, Object>> memories = personalizationEnabled
                ? agentMemoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                        .eq(AgentMemory::getUserId, userId)
                        .and(wrapper -> wrapper.isNull(AgentMemory::getExpiresAt)
                                .or().ge(AgentMemory::getExpiresAt, LocalDate.now()))
                        .orderByDesc(AgentMemory::getUpdatedAt)
                        .last("LIMIT 20"))
                        .stream().map(memory -> {
                            Map<String, Object> item = new java.util.LinkedHashMap<>();
                            item.put("content", memory.getContent());
                            item.put("source", "用户主动添加");
                            item.put("updatedAt", memory.getUpdatedAt() == null ? null : memory.getUpdatedAt().toString());
                            item.put("expiresAt", memory.getExpiresAt() == null ? null : memory.getExpiresAt().toString());
                            return item;
                        }).collect(Collectors.toList())
                : List.of();
        return Map.of("asOfDate", LocalDate.now().toString(),
                "emotionHistory", history,
                "approvedMemories", memories,
                "personalizationEnabled", personalizationEnabled);
    }

    private String donePayload() {
        return json(Map.of("code", "200", "data", Map.of("done", true)));
    }

    private String errorPayload() {
        return json(Map.of("code", "500", "message", "AI回复失败，请稍后重试"));
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Unable to serialize stream event", error);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    @Override
    public Map<String, Object> getSessionEmotion(Long sessionId, Long userId, Integer userType) {
        ConsultationSession session = requireReadableSession(sessionId, userId, userType);
        recordReviewRead(session, userId, "READ_EMOTION");
        List<ConsultationMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ConsultationMessage>()
                        .eq(ConsultationMessage::getSessionId, sessionId)
                        .orderByAsc(ConsultationMessage::getCreatedAt));
        return chatAgent.analyzeEmotion(sessionId, messages, session.getUserId());
    }

    @Override
    @Transactional
    public void updateReviewConsent(Long sessionId, Long userId, Boolean allowed) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "仅会话所有者可管理复核授权");
        }

        boolean nextConsent = Boolean.TRUE.equals(allowed);
        boolean previousConsent = Boolean.TRUE.equals(session.getReviewConsent());
        if (previousConsent == nextConsent) {
            return;
        }

        session.setReviewConsent(nextConsent);
        session.setReviewConsentUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);

        ConsultationReviewAudit audit = new ConsultationReviewAudit();
        audit.setSessionId(sessionId);
        audit.setActorUserId(userId);
        audit.setAction(nextConsent ? "CONSENT_GRANTED" : "CONSENT_REVOKED");
        reviewAuditMapper.insert(audit);

        if (!nextConsent && ("PENDING".equals(session.getReviewStatus())
                || "IN_PROGRESS".equals(session.getReviewStatus()))) {
            String previousStatus = session.getReviewStatus();
            session.setReviewStatus("CANCELLED");
            session.setReviewAssigneeId(null);
            session.setReviewVersion((session.getReviewVersion() == null ? 0 : session.getReviewVersion()) + 1);
            session.setReviewUpdatedAt(LocalDateTime.now());
            sessionMapper.updateById(session);
            recordReviewTransition(sessionId, userId, "REVIEW_CANCELLED", previousStatus, "CANCELLED");
        }
    }

    @Override
    @Transactional
    public void requestReview(Long sessionId, Long userId) {
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "仅会话所有者可申请人工复核");
        }
        if (!Boolean.TRUE.equals(session.getReviewConsent())) {
            throw new BusinessException("409", "请先开启人工复核授权");
        }
        String previousStatus = session.getReviewStatus() == null ? "NONE" : session.getReviewStatus();
        if ("PENDING".equals(previousStatus) || "IN_PROGRESS".equals(previousStatus)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        session.setReviewStatus("PENDING");
        session.setReviewAssigneeId(null);
        session.setReviewVersion((session.getReviewVersion() == null ? 0 : session.getReviewVersion()) + 1);
        session.setReviewRequestedAt(now);
        session.setReviewUpdatedAt(now);
        sessionMapper.updateById(session);
        recordReviewTransition(sessionId, userId, "REVIEW_REQUESTED", previousStatus, "PENDING");
    }

    @Override
    @Transactional
    public void updateReviewState(Long sessionId, Long actorUserId, Integer actorUserType,
                                  ReviewStateRequest request) {
        if (actorUserId == null || !Integer.valueOf(2).equals(actorUserType)) {
            throw new BusinessException("403", "仅授权复核人员可更新复核状态");
        }
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }
        if (!Boolean.TRUE.equals(session.getReviewConsent())) {
            throw new BusinessException("403", "用户已撤回人工复核授权");
        }

        int currentVersion = session.getReviewVersion() == null ? 0 : session.getReviewVersion();
        if (request.getExpectedVersion() != currentVersion) {
            throw new BusinessException("409", "复核状态已被其他人员更新，请刷新后重试");
        }

        String fromStatus = session.getReviewStatus() == null ? "NONE" : session.getReviewStatus();
        String target = request.getTargetStatus().trim().toUpperCase();
        String action;
        if ("PENDING".equals(fromStatus) && "IN_PROGRESS".equals(target)) {
            session.setReviewAssigneeId(actorUserId);
            action = "REVIEW_CLAIMED";
        } else if ("IN_PROGRESS".equals(fromStatus) && "RESOLVED".equals(target)) {
            if (!actorUserId.equals(session.getReviewAssigneeId())) {
                throw new BusinessException("403", "只有当前负责人可以完成复核");
            }
            action = "REVIEW_RESOLVED";
        } else if ("IN_PROGRESS".equals(fromStatus) && "PENDING".equals(target)) {
            if (!actorUserId.equals(session.getReviewAssigneeId())) {
                throw new BusinessException("403", "只有当前负责人可以释放复核事项");
            }
            session.setReviewAssigneeId(null);
            action = "REVIEW_RELEASED";
        } else {
            throw new BusinessException("409", "不允许的复核状态迁移");
        }

        session.setReviewStatus(target);
        session.setReviewVersion(currentVersion + 1);
        session.setReviewUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
        recordReviewTransition(sessionId, actorUserId, action, fromStatus, target);
    }

    private void recordReviewTransition(Long sessionId, Long actorUserId,
                                        String action, String fromStatus, String toStatus) {
        ConsultationReviewAudit audit = new ConsultationReviewAudit();
        audit.setSessionId(sessionId);
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        audit.setFromStatus(fromStatus);
        audit.setToStatus(toStatus);
        reviewAuditMapper.insert(audit);
    }

    private ConsultationSession requireReadableSession(Long sessionId, Long userId, Integer userType) {
        ConsultationSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException("会话不存在");
        }

        boolean isOwner = userId != null && userId.equals(session.getUserId());
        boolean isReviewer = Integer.valueOf(2).equals(userType)
                && Boolean.TRUE.equals(session.getReviewConsent());
        boolean reviewInProgress = "PENDING".equals(session.getReviewStatus())
                || "IN_PROGRESS".equals(session.getReviewStatus())
                || "RESOLVED".equals(session.getReviewStatus());
        if (!isOwner && !(isReviewer && reviewInProgress)) {
            throw new BusinessException("403", "无权访问该会话");
        }
        return session;
    }

    private void recordReviewRead(ConsultationSession session, Long actorUserId, String action) {
        if (actorUserId == null || actorUserId.equals(session.getUserId())) {
            return;
        }
        ConsultationReviewAudit audit = new ConsultationReviewAudit();
        audit.setSessionId(session.getId());
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        reviewAuditMapper.insert(audit);
    }

}
