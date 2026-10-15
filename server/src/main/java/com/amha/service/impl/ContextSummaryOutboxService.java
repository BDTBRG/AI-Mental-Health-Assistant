package com.amha.service.impl;

import com.amha.common.BusinessException;
import com.amha.dto.ContextSummaryAgentRequest;
import com.amha.dto.ContextSummaryAgentResponse;
import com.amha.dto.ContextSummaryMessage;
import com.amha.entity.ConsultationContextSummary;
import com.amha.entity.ConsultationContextSummaryOutbox;
import com.amha.entity.ConsultationMessage;
import com.amha.entity.ConsultationSession;
import com.amha.mapper.ConsultationContextSummaryMapper;
import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.amha.mapper.ConsultationSessionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ContextSummaryOutboxService {
    private static final String PROMPT_VERSION = "context-summary-v1";
    private static final int MAX_ATTEMPTS = 8;
    private static final int MAX_BATCH_MESSAGES = 40;
    private static final int MAX_BATCH_CHARS = 16_000;
    private static final int MAX_SUMMARY_JSON_CHARS = 12_000;
    private static final long MAX_BACKOFF_SECONDS = 900;
    private static final long DISPATCH_LEASE_SECONDS = 30;
    private static final TypeReference<Map<String, Object>> SUMMARY_TYPE = new TypeReference<>() {};

    private final ConsultationContextSummaryOutboxMapper outboxMapper;
    private final ConsultationContextSummaryMapper summaryMapper;
    private final ConsultationMessageMapper messageMapper;
    private final ConsultationSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final ContextSummaryMetrics metrics;
    @Value("${amha.chat.context-summary.processing-lease:PT90S}")
    private Duration processingLease = Duration.ofSeconds(90);
    @Value("${agent.context-summary-timeout-ms:65000}")
    private long agentTimeoutMillis = 65_000;

    public record ContextSummaryClaim(Long outboxId, Long sessionId, Long userId,
                                      Long targetThroughMessageId, Long expectedSummaryVersion,
                                      String processingLeaseToken) {
    }

    @PostConstruct
    void validateProcessingLease() {
        if (agentTimeoutMillis <= 0
                || processingLease.compareTo(Duration.ofMillis(agentTimeoutMillis + 25_000)) < 0) {
            throw new IllegalStateException(
                    "context summary processing lease must exceed the Agent timeout by at least 25 seconds");
        }
    }

    @Transactional
    public void enqueue(Long sessionId, Long userId, Long targetThroughMessageId) {
        if (sessionId == null || userId == null || targetThroughMessageId == null || targetThroughMessageId <= 0) {
            throw new BusinessException("400", "摘要任务参数无效");
        }
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        requireOwner(session, userId);
        Long latestMessageId = messageMapper.selectMaxMessageId(sessionId);
        if (latestMessageId == null || targetThroughMessageId > latestMessageId) {
            throw new BusinessException("409", "摘要目标超出该会话已保存的消息范围");
        }

        ConsultationContextSummaryOutbox task = outboxMapper.selectBySessionForUpdate(sessionId);
        ConsultationContextSummary summary = summaryMapper.selectBySessionAndUserForUpdate(sessionId, userId);
        long existingTarget = task == null ? 0L : value(task.getDesiredThroughMessageId());
        long requiredTarget = Math.max(targetThroughMessageId, existingTarget);
        if (summary != null && value(summary.getCoveredThroughMessageId()) >= requiredTarget) {
            if (task != null) outboxMapper.deleteById(task.getId());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        if (task == null) {
            task = new ConsultationContextSummaryOutbox();
            task.setSessionId(sessionId);
            task.setUserId(userId);
            task.setDesiredThroughMessageId(targetThroughMessageId);
            task.setStatus("PENDING");
            task.setAttemptCount(0);
            task.setNextAttemptAt(now);
            task.setManualRetryCount(0);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            outboxMapper.insert(task);
            return;
        }

        if (!userId.equals(task.getUserId())) {
            throw new BusinessException("409", "摘要任务归属与会话不一致");
        }
        long previousTarget = value(task.getDesiredThroughMessageId());
        boolean targetAdvanced = targetThroughMessageId > previousTarget;
        if (!targetAdvanced) return;
        task.setDesiredThroughMessageId(Math.max(previousTarget, targetThroughMessageId));
        // A growing target does not reset the retry budget or revive DEAD work.
        // Only successful batch completion or an audited administrator retry resets attempts.
        task.setUpdatedAt(now);
        outboxMapper.updateById(task);
    }

    /** Claims the next database-owned retry for publication to Redis Streams. */
    @Transactional
    public Long claimNextForDispatch() {
        ConsultationContextSummaryOutbox task = outboxMapper.selectNextReadyForUpdate();
        if (task == null) return null;
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("QUEUED");
        task.setLeaseToken(UUID.randomUUID().toString());
        task.setLeaseUntil(now.plusSeconds(DISPATCH_LEASE_SECONDS));
        task.setUpdatedAt(now);
        outboxMapper.updateById(task);
        return task.getId();
    }

    /** Claims a queued or expired delivery and snapshots its current target/version. */
    @Transactional
    public ContextSummaryClaim claimForProcessing(Long outboxId) {
        ConsultationContextSummaryOutbox observed = outboxMapper.selectById(outboxId);
        if (observed == null) return null;

        ConsultationSession session = sessionMapper.selectByIdForUpdate(observed.getSessionId());
        if (session == null || !Objects.equals(session.getUserId(), observed.getUserId())) return null;
        ConsultationContextSummaryOutbox task = outboxMapper.selectByIdForUpdate(outboxId);
        if (task == null || !Objects.equals(task.getSessionId(), session.getId())
                || !Objects.equals(task.getUserId(), session.getUserId())) return null;

        LocalDateTime now = LocalDateTime.now();
        boolean queued = "QUEUED".equals(task.getStatus());
        boolean expiredProcessing = "PROCESSING".equals(task.getStatus())
                && (task.getLeaseUntil() == null || !task.getLeaseUntil().isAfter(now));
        if (!queued && !expiredProcessing) return null;

        if (value(task.getAttemptCount()) >= MAX_ATTEMPTS) {
            task.setStatus("DEAD");
            task.setLastErrorCode("MAX_ATTEMPTS_EXCEEDED");
            task.setUpdatedAt(now);
            clearLease(task);
            outboxMapper.updateById(task);
            metrics.recordFailed();
            return null;
        }

        ConsultationContextSummary summary = summaryMapper.selectBySessionAndUserForUpdate(
                task.getSessionId(), task.getUserId());
        long summaryVersion = summary == null ? 0L : value(summary.getSummaryVersion());
        if (summary != null && value(summary.getCoveredThroughMessageId()) >= value(task.getDesiredThroughMessageId())) {
            outboxMapper.deleteById(task.getId());
            return null;
        }

        String leaseToken = UUID.randomUUID().toString();
        task.setStatus("PROCESSING");
        task.setAttemptCount(value(task.getAttemptCount()) + 1);
        task.setLeaseToken(leaseToken);
        task.setLeaseUntil(now.plus(processingLease));
        task.setUpdatedAt(now);
        outboxMapper.updateById(task);
        return new ContextSummaryClaim(task.getId(), task.getSessionId(), task.getUserId(),
                task.getDesiredThroughMessageId(), summaryVersion, leaseToken);
    }

    @Transactional
    public ContextSummaryAgentRequest loadRequest(ContextSummaryClaim claim, int maxMessages, int maxChars) {
        if (claim == null || maxMessages <= 0 || maxChars <= 0) {
            throw new BusinessException("400", "摘要读取参数无效");
        }
        int boundedMessageLimit = Math.min(maxMessages, MAX_BATCH_MESSAGES);
        int boundedCharLimit = Math.min(maxChars, MAX_BATCH_CHARS);
        ConsultationSession session = sessionMapper.selectByIdForUpdate(claim.sessionId());
        requireOwner(session, claim.userId());
        ConsultationContextSummaryOutbox task = outboxMapper.selectByIdForUpdate(claim.outboxId());
        requireCurrentClaim(task, claim);
        ConsultationContextSummary summary = summaryMapper.selectBySessionAndUserForUpdate(
                claim.sessionId(), claim.userId());
        long actualVersion = summary == null ? 0L : value(summary.getSummaryVersion());
        if (actualVersion != claim.expectedSummaryVersion()) {
            throw new BusinessException("409", "摘要版本已变化，请重新领取任务");
        }

        long watermark = summary == null ? 0L : value(summary.getCoveredThroughMessageId());
        List<ConsultationMessage> rows = messageMapper.selectWindowAfterWatermark(
                claim.sessionId(), watermark, claim.targetThroughMessageId(), boundedMessageLimit);
        List<ContextSummaryMessage> bounded = new ArrayList<>();
        int charCount = 0;
        for (ConsultationMessage row : rows == null ? List.<ConsultationMessage>of() : rows) {
            if (bounded.size() >= boundedMessageLimit) break;
            if (row.getId() == null || row.getId() <= watermark || row.getId() > claim.targetThroughMessageId()
                    || !Objects.equals(row.getSessionId(), claim.sessionId())) continue;
            String content = row.getContent() == null ? "" : row.getContent();
            int length = content.codePointCount(0, content.length());
            if (length > boundedCharLimit && bounded.isEmpty()) {
                metrics.recordContextTooLarge();
                throw new BusinessException("CONTEXT_TOO_LARGE", "摘要消息超过单批字符上限");
            }
            if (charCount + length > boundedCharLimit) break;
            bounded.add(new ContextSummaryMessage(row.getId(), row.getSenderType(), content));
            charCount += length;
        }
        if (bounded.isEmpty()) {
            metrics.recordContextUnavailable();
            throw new BusinessException("CONTEXT_UNAVAILABLE", "当前摘要水位没有可处理的消息");
        }

        Map<String, Object> previousSummary = null;
        if (summary != null && summary.getSummaryJson() != null && !summary.getSummaryJson().isBlank()) {
            if (summary.getSummaryJson().codePointCount(0, summary.getSummaryJson().length())
                    > MAX_SUMMARY_JSON_CHARS) {
                metrics.recordContextTooLarge();
                throw new BusinessException("CONTEXT_TOO_LARGE", "历史摘要超过大小上限");
            }
            try {
                previousSummary = objectMapper.readValue(summary.getSummaryJson(), SUMMARY_TYPE);
            } catch (Exception ignored) {
                metrics.recordContextUnavailable();
                throw new BusinessException("CONTEXT_UNAVAILABLE", "历史摘要暂不可用");
            }
        }
        return new ContextSummaryAgentRequest(claim.sessionId(), claim.userId(), previousSummary,
                List.copyOf(bounded), PROMPT_VERSION, UUID.randomUUID().toString());
    }

    @Transactional
    public void complete(ContextSummaryClaim claim, ContextSummaryAgentResponse response) {
        if (claim == null || response == null || response.summary() == null
                || response.coveredThroughMessageId() == null
                || !PROMPT_VERSION.equals(response.promptVersion())) {
            throw new BusinessException("CONTEXT_INVALID_RESPONSE", "摘要响应格式无效");
        }
        ConsultationSession session = sessionMapper.selectByIdForUpdate(claim.sessionId());
        if (session == null || !Objects.equals(session.getUserId(), claim.userId())) return;
        ConsultationContextSummaryOutbox task = outboxMapper.selectByIdForUpdate(claim.outboxId());
        if (!isCurrentClaim(task, claim)) return;
        ConsultationContextSummary summary = summaryMapper.selectBySessionAndUserForUpdate(
                claim.sessionId(), claim.userId());
        long watermark = summary == null ? 0L : value(summary.getCoveredThroughMessageId());
        long nextWatermark = response.coveredThroughMessageId();
        if (nextWatermark <= watermark || nextWatermark > claim.targetThroughMessageId()) {
            throw new BusinessException("CONTEXT_INVALID_RESPONSE", "摘要响应水位无效");
        }
        long currentVersion = summary == null ? 0L : value(summary.getSummaryVersion());
        if (currentVersion != claim.expectedSummaryVersion()) {
            throw new BusinessException("409", "摘要版本已变化，拒绝覆盖新摘要");
        }

        final String json;
        try {
            json = objectMapper.writeValueAsString(response.summary());
        } catch (Exception error) {
            throw new BusinessException("CONTEXT_INVALID_RESPONSE", "摘要响应无法序列化");
        }
        if (json.codePointCount(0, json.length()) > MAX_SUMMARY_JSON_CHARS) {
            metrics.recordContextTooLarge();
            throw new BusinessException("CONTEXT_TOO_LARGE", "摘要响应超过大小上限");
        }
        LocalDateTime now = LocalDateTime.now();
        if (summary == null) {
            summary = new ConsultationContextSummary();
            summary.setSessionId(claim.sessionId());
            summary.setUserId(claim.userId());
            summary.setSummaryJson(json);
            summary.setCoveredThroughMessageId(nextWatermark);
            summary.setSummaryVersion(1L);
            summary.setPromptVersion(response.promptVersion());
            summary.setCreatedAt(now);
            summary.setUpdatedAt(now);
            summaryMapper.insert(summary);
        } else {
            int changed = summaryMapper.updateSummaryCas(claim.sessionId(), claim.userId(), currentVersion,
                    json, nextWatermark, response.promptVersion(), now);
            if (changed != 1) throw new BusinessException("409", "摘要版本已变化，拒绝覆盖新摘要");
        }

        if (value(task.getDesiredThroughMessageId()) <= nextWatermark) {
            outboxMapper.deleteById(task.getId());
        } else {
            task.setStatus("PENDING");
            task.setAttemptCount(0);
            task.setNextAttemptAt(now);
            task.setLastErrorCode(null);
            task.setUpdatedAt(now);
            clearLease(task);
            outboxMapper.updateById(task);
        }
    }

    @Transactional
    public void fail(ContextSummaryClaim claim, String errorCode) {
        if (claim == null) return;
        ConsultationSession session = sessionMapper.selectByIdForUpdate(claim.sessionId());
        if (session == null || !Objects.equals(session.getUserId(), claim.userId())) return;
        ConsultationContextSummaryOutbox task = outboxMapper.selectByIdForUpdate(claim.outboxId());
        if (!isCurrentClaim(task, claim)) return;
        LocalDateTime now = LocalDateTime.now();
        int attempts = value(task.getAttemptCount());
        task.setLastErrorCode(safeErrorCode(errorCode));
        task.setUpdatedAt(now);
        clearLease(task);
        if (attempts >= MAX_ATTEMPTS) {
            task.setStatus("DEAD");
        } else {
            task.setStatus("PENDING");
            task.setNextAttemptAt(now.plusSeconds(backoffSeconds(attempts)));
        }
        outboxMapper.updateById(task);
        metrics.recordFailed();
    }

    @Transactional
    public boolean retryDead(Long sessionId, Long actorUserId) {
        if (sessionId == null || actorUserId == null) {
            throw new BusinessException("400", "人工重放记录缺少操作人或会话");
        }
        ConsultationSession session = sessionMapper.selectByIdForUpdate(sessionId);
        if (session == null) return false;
        ConsultationContextSummaryOutbox task = outboxMapper.selectBySessionForUpdate(sessionId);
        if (task == null || !"DEAD".equals(task.getStatus())) return false;
        LocalDateTime now = LocalDateTime.now();
        task.setStatus("PENDING");
        task.setAttemptCount(0);
        task.setNextAttemptAt(now);
        task.setLastErrorCode(null);
        task.setManualRetryCount(value(task.getManualRetryCount()) + 1);
        task.setLastManualRetryBy(actorUserId);
        task.setLastManualRetryAt(now);
        task.setUpdatedAt(now);
        clearLease(task);
        outboxMapper.updateById(task);
        return true;
    }

    @Transactional
    public void deleteForSession(Long sessionId) {
        if (sessionId == null) return;
        sessionMapper.selectByIdForUpdate(sessionId);
        outboxMapper.deleteBySession(sessionId);
        summaryMapper.deleteById(sessionId);
    }

    private void requireOwner(ConsultationSession session, Long userId) {
        if (session == null) throw new BusinessException("404", "会话不存在");
        if (userId == null || !userId.equals(session.getUserId())) {
            throw new BusinessException("403", "无权访问该会话");
        }
    }

    private void requireCurrentClaim(ConsultationContextSummaryOutbox task, ContextSummaryClaim claim) {
        if (!isCurrentClaim(task, claim)) {
            throw new BusinessException("409", "摘要任务已过期");
        }
    }

    private boolean isCurrentClaim(ConsultationContextSummaryOutbox task, ContextSummaryClaim claim) {
        return task != null && "PROCESSING".equals(task.getStatus())
                && Objects.equals(task.getLeaseToken(), claim.processingLeaseToken())
                && Objects.equals(task.getSessionId(), claim.sessionId())
                && Objects.equals(task.getUserId(), claim.userId());
    }

    private void clearLease(ConsultationContextSummaryOutbox task) {
        task.setLeaseToken(null);
        task.setLeaseUntil(null);
    }

    private long backoffSeconds(int attempts) {
        int exponent = Math.min(30, Math.max(0, attempts - 1));
        return Math.min(MAX_BACKOFF_SECONDS, 5L << exponent);
    }

    private String safeErrorCode(String errorCode) {
        if (errorCode == null || !errorCode.matches("[A-Z0-9_]{1,64}")) return "SUMMARY_PROCESSING_FAILED";
        return errorCode;
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
