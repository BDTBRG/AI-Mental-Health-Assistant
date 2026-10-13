package com.amha.service.impl;

import com.amha.entity.AgentCheckpointPurgeOutbox;
import com.amha.entity.ConsultationSession;
import com.amha.mapper.AgentCheckpointPurgeOutboxMapper;
import com.amha.mapper.ConsultationSessionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AgentCheckpointPurgeOutboxService {
    private final AgentCheckpointPurgeOutboxMapper outboxMapper;
    private final ConsultationSessionMapper sessionMapper;

    @Transactional
    public void enqueueSession(Long sessionId, Long userId) {
        AgentCheckpointPurgeOutbox task = outboxMapper.selectBySessionForUpdate(sessionId);
        LocalDateTime now = LocalDateTime.now();
        if (task == null) {
            task = new AgentCheckpointPurgeOutbox();
            task.setSessionId(sessionId);
            task.setUserId(userId);
            task.setDesiredVersion(1L);
            task.setStatus("PENDING");
            task.setAttempts(0);
            task.setNextAttemptAt(now);
            outboxMapper.insert(task);
            return;
        }

        task.setUserId(userId);
        task.setDesiredVersion((task.getDesiredVersion() == null ? 0L : task.getDesiredVersion()) + 1L);
        task.setLastError(null);
        task.setUpdatedAt(now);
        if (!"PROCESSING".equals(task.getStatus())) {
            task.setStatus("PENDING");
            task.setAttempts(0);
            task.setNextAttemptAt(now);
            clearLease(task);
        }
        outboxMapper.updateById(task);
    }

    @Transactional
    public void enqueueUserSessions(Long userId) {
        if (userId == null) return;
        for (ConsultationSession session : sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConsultationSession>()
                        .eq(ConsultationSession::getUserId, userId))) {
            enqueueSession(session.getId(), userId);
        }
    }

    @Transactional
    public AgentCheckpointPurgeOutbox claimNextForProcessing() {
        AgentCheckpointPurgeOutbox task = outboxMapper.selectNextReadyForUpdate();
        if (task == null) return null;
        task.setStatus("PROCESSING");
        task.setProcessingVersion(task.getDesiredVersion());
        task.setAttempts((task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
        task.setLeaseToken(UUID.randomUUID().toString());
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(2));
        task.setUpdatedAt(LocalDateTime.now());
        outboxMapper.updateById(task);
        return task;
    }

    @Transactional
    public boolean complete(AgentCheckpointPurgeOutbox claimedTask) {
        AgentCheckpointPurgeOutbox task = outboxMapper.selectByIdForUpdate(claimedTask.getId());
        if (!isCurrentClaim(task, claimedTask)) return false;
        LocalDateTime now = LocalDateTime.now();
        boolean changedDuringPurge = !Objects.equals(task.getDesiredVersion(), task.getProcessingVersion());
        task.setStatus(changedDuringPurge ? "PENDING" : "DONE");
        task.setAttempts(changedDuringPurge ? 0 : task.getAttempts());
        task.setNextAttemptAt(changedDuringPurge ? now : task.getNextAttemptAt());
        task.setLastError(null);
        task.setUpdatedAt(now);
        clearLease(task);
        outboxMapper.updateById(task);
        return true;
    }

    @Transactional
    public boolean fail(AgentCheckpointPurgeOutbox claimedTask, String errorCode) {
        AgentCheckpointPurgeOutbox task = outboxMapper.selectByIdForUpdate(claimedTask.getId());
        if (!isCurrentClaim(task, claimedTask)) return false;
        LocalDateTime now = LocalDateTime.now();
        boolean changedDuringPurge = !Objects.equals(task.getDesiredVersion(), task.getProcessingVersion());
        int attempts = task.getAttempts() == null ? 1 : task.getAttempts();
        long delaySeconds = Math.min(3600, 5L << Math.min(10, Math.max(0, attempts - 1)));
        // A failed purge must remain durable because the checkpoint can contain revoked private data.
        task.setStatus("PENDING");
        task.setAttempts(changedDuringPurge ? 0 : attempts);
        task.setNextAttemptAt(changedDuringPurge ? now : now.plusSeconds(delaySeconds));
        task.setLastError(errorCode);
        task.setUpdatedAt(now);
        clearLease(task);
        outboxMapper.updateById(task);
        return true;
    }

    private boolean isCurrentClaim(AgentCheckpointPurgeOutbox current,
                                   AgentCheckpointPurgeOutbox claimed) {
        return current != null && "PROCESSING".equals(current.getStatus())
                && Objects.equals(current.getLeaseToken(), claimed.getLeaseToken());
    }

    private void clearLease(AgentCheckpointPurgeOutbox task) {
        task.setLeaseToken(null);
        task.setLeaseUntil(null);
        task.setProcessingVersion(null);
    }
}
