package com.amha.service.impl;

import com.amha.agent.RealChatAgent;
import com.amha.entity.EmotionAnalysisOutbox;
import com.amha.entity.EmotionDiary;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmotionAnalysisOutboxWorkerTest {

    @Test
    void doesNotSendDiaryContentAfterDispatchFenceRejectsTheClaim() {
        EmotionAnalysisOutboxService outboxService = mock(EmotionAnalysisOutboxService.class);
        RealChatAgent realChatAgent = mock(RealChatAgent.class);
        EmotionAnalysisOutbox task = task();
        EmotionAnalysisOutboxWorker worker = new EmotionAnalysisOutboxWorker(
                outboxService, realChatAgent, null, null);
        when(outboxService.loadDiaryForDispatch(task)).thenReturn(null);

        worker.processTask(task);

        verify(realChatAgent, never()).analyzeDiary(anyMap());
        verify(outboxService).fail(task, "DIARY_NOT_FOUND");
    }

    @Test
    void sendsTheDiaryReturnedByTheDispatchFence() {
        EmotionAnalysisOutboxService outboxService = mock(EmotionAnalysisOutboxService.class);
        RealChatAgent realChatAgent = mock(RealChatAgent.class);
        EmotionAnalysisOutbox task = task();
        EmotionDiary diary = new EmotionDiary();
        diary.setId(22L);
        diary.setUserId(8L);
        diary.setDiaryContent("当前仍然存在的日记");
        EmotionAnalysisOutboxWorker worker = new EmotionAnalysisOutboxWorker(
                outboxService, realChatAgent, null, null);
        when(outboxService.loadDiaryForDispatch(task)).thenReturn(diary);
        when(realChatAgent.analyzeDiary(anyMap())).thenReturn(Mono.just("analysis"));

        worker.processTask(task);

        org.mockito.ArgumentCaptor<Map<String, Object>> payload =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(realChatAgent).analyzeDiary(payload.capture());
        org.junit.jupiter.api.Assertions.assertEquals("当前仍然存在的日记", payload.getValue().get("diaryContent"));
        verify(outboxService).complete(task, "analysis");
    }

    private EmotionAnalysisOutbox task() {
        EmotionAnalysisOutbox task = new EmotionAnalysisOutbox();
        task.setId(11L);
        task.setDiaryId(22L);
        task.setLeaseToken("lease-current");
        task.setAttempts(1);
        return task;
    }
}
