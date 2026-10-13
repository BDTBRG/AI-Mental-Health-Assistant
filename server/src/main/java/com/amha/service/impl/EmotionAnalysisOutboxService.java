package com.amha.service.impl;

import com.amha.entity.EmotionAnalysisOutbox;
import com.amha.entity.EmotionDiary;
import com.amha.mapper.EmotionDiaryMapper;
import com.amha.mapper.EmotionAnalysisOutboxMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmotionAnalysisOutboxService {
    private static final int MAX_ATTEMPTS = 5;
    private final EmotionAnalysisOutboxMapper outboxMapper;
    private final EmotionDiaryMapper diaryMapper;

    @Transactional
    public EmotionAnalysisOutbox claimNextForDispatch() {
        EmotionAnalysisOutbox task = outboxMapper.selectNextReadyForUpdate();
        if (task == null) return null;

        task.setStatus("QUEUED");
        task.setLeaseToken(UUID.randomUUID().toString());
        task.setLeaseUntil(LocalDateTime.now().plusSeconds(30));
        task.setUpdatedAt(LocalDateTime.now());
        outboxMapper.updateById(task);
        return task;
    }

    @Transactional
    public EmotionAnalysisOutbox claimForProcessing(Long taskId, String dispatchToken) {
        EmotionAnalysisOutbox task = outboxMapper.selectByIdForUpdate(taskId);
        if (task == null || !"QUEUED".equals(task.getStatus())
                || !java.util.Objects.equals(dispatchToken, task.getLeaseToken())) return null;

        task.setStatus("PROCESSING");
        task.setAttempts((task.getAttempts() == null ? 0 : task.getAttempts()) + 1);
        task.setLeaseToken(UUID.randomUUID().toString());
        task.setLeaseUntil(LocalDateTime.now().plusMinutes(2));
        task.setUpdatedAt(LocalDateTime.now());
        outboxMapper.updateById(task);
        return task;
    }

    @Transactional
    public boolean returnToPending(Long taskId, String dispatchToken) {
        LocalDateTime now = LocalDateTime.now();
        return outboxMapper.update(null, new LambdaUpdateWrapper<EmotionAnalysisOutbox>()
                .eq(EmotionAnalysisOutbox::getId, taskId)
                .eq(EmotionAnalysisOutbox::getStatus, "QUEUED")
                .eq(EmotionAnalysisOutbox::getLeaseToken, dispatchToken)
                .set(EmotionAnalysisOutbox::getStatus, "PENDING")
                .set(EmotionAnalysisOutbox::getNextAttemptAt, now.plusSeconds(5))
                .set(EmotionAnalysisOutbox::getLeaseToken, null)
                .set(EmotionAnalysisOutbox::getLeaseUntil, null)
                .set(EmotionAnalysisOutbox::getLastError, "REDIS_PUBLISH_FAILED")
                .set(EmotionAnalysisOutbox::getUpdatedAt, now)) > 0;
    }

    @Transactional
    public boolean complete(EmotionAnalysisOutbox task, String analysis) {
        boolean updated = outboxMapper.update(null, new LambdaUpdateWrapper<EmotionAnalysisOutbox>()
                .eq(EmotionAnalysisOutbox::getId, task.getId())
                .eq(EmotionAnalysisOutbox::getStatus, "PROCESSING")
                .eq(EmotionAnalysisOutbox::getLeaseToken, task.getLeaseToken())
                .set(EmotionAnalysisOutbox::getStatus, "DONE")
                .set(EmotionAnalysisOutbox::getLeaseToken, null)
                .set(EmotionAnalysisOutbox::getLeaseUntil, null)
                .set(EmotionAnalysisOutbox::getLastError, null)
                .set(EmotionAnalysisOutbox::getUpdatedAt, LocalDateTime.now())) > 0;
        if (!updated) return false;

        LocalDateTime now = LocalDateTime.now();
        diaryMapper.update(null, new LambdaUpdateWrapper<EmotionDiary>()
                .eq(EmotionDiary::getId, task.getDiaryId())
                .set(EmotionDiary::getAiEmotionAnalysis, analysis)
                .set(EmotionDiary::getHasAiEmotionAnalysis, 1)
                .set(EmotionDiary::getAiAnalysisStatus, "COMPLETED")
                .set(EmotionDiary::getAiAnalysisUpdatedAt, now));
        return true;
    }

    @Transactional
    public boolean fail(EmotionAnalysisOutbox task, String errorCode) {
        int attempts = task.getAttempts() == null ? 1 : task.getAttempts();
        boolean exhausted = attempts >= MAX_ATTEMPTS;
        long delaySeconds = Math.min(300, 5L << Math.max(0, attempts - 1));
        LocalDateTime now = LocalDateTime.now();
        boolean updated = outboxMapper.update(null, new LambdaUpdateWrapper<EmotionAnalysisOutbox>()
                .eq(EmotionAnalysisOutbox::getId, task.getId())
                .eq(EmotionAnalysisOutbox::getStatus, "PROCESSING")
                .eq(EmotionAnalysisOutbox::getLeaseToken, task.getLeaseToken())
                .set(EmotionAnalysisOutbox::getStatus, exhausted ? "DEAD" : "PENDING")
                .set(EmotionAnalysisOutbox::getNextAttemptAt, now.plusSeconds(delaySeconds))
                .set(EmotionAnalysisOutbox::getLeaseToken, null)
                .set(EmotionAnalysisOutbox::getLeaseUntil, null)
                .set(EmotionAnalysisOutbox::getLastError, errorCode)
                .set(EmotionAnalysisOutbox::getUpdatedAt, now)) > 0;
        if (updated && exhausted) {
            diaryMapper.update(null, new LambdaUpdateWrapper<EmotionDiary>()
                    .eq(EmotionDiary::getId, task.getDiaryId())
                    .set(EmotionDiary::getAiAnalysisStatus, "FAILED")
                    .set(EmotionDiary::getAiAnalysisUpdatedAt, now));
        }
        return updated;
    }
}
