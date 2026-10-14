package com.amha.service.impl;

import com.amha.entity.EmotionAnalysisOutbox;
import com.amha.entity.EmotionDiary;
import com.amha.mapper.EmotionAnalysisOutboxMapper;
import com.amha.mapper.EmotionDiaryMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmotionAnalysisOutboxServiceTest {

    @Test
    void loadsDiaryOnlyWhileTheClaimedProcessingLeaseIsCurrent() {
        EmotionAnalysisOutboxMapper outboxMapper = mock(EmotionAnalysisOutboxMapper.class);
        EmotionDiaryMapper diaryMapper = mock(EmotionDiaryMapper.class);
        EmotionAnalysisOutboxService service = new EmotionAnalysisOutboxService(outboxMapper, diaryMapper);
        EmotionAnalysisOutbox claimedTask = task("lease-current");
        EmotionAnalysisOutbox currentTask = task("lease-current");
        currentTask.setStatus("PROCESSING");
        EmotionDiary diary = new EmotionDiary();
        diary.setId(22L);

        when(outboxMapper.selectByIdForUpdate(11L)).thenReturn(currentTask);
        when(diaryMapper.selectByIdForUpdate(22L)).thenReturn(diary);

        EmotionDiary loaded = service.loadDiaryForDispatch(claimedTask);

        assertSame(diary, loaded);
        verify(diaryMapper).selectByIdForUpdate(22L);
    }

    @Test
    void doesNotLoadDiaryWhenTheOutboxTaskWasDeleted() {
        EmotionAnalysisOutboxMapper outboxMapper = mock(EmotionAnalysisOutboxMapper.class);
        EmotionDiaryMapper diaryMapper = mock(EmotionDiaryMapper.class);
        EmotionAnalysisOutboxService service = new EmotionAnalysisOutboxService(outboxMapper, diaryMapper);
        EmotionAnalysisOutbox claimedTask = task("lease-current");

        when(outboxMapper.selectByIdForUpdate(11L)).thenReturn(null);

        assertNull(service.loadDiaryForDispatch(claimedTask));
        verify(diaryMapper, never()).selectByIdForUpdate(22L);
    }

    @Test
    void doesNotLoadDiaryForAReclaimedLease() {
        EmotionAnalysisOutboxMapper outboxMapper = mock(EmotionAnalysisOutboxMapper.class);
        EmotionDiaryMapper diaryMapper = mock(EmotionDiaryMapper.class);
        EmotionAnalysisOutboxService service = new EmotionAnalysisOutboxService(outboxMapper, diaryMapper);
        EmotionAnalysisOutbox claimedTask = task("old-lease");
        EmotionAnalysisOutbox currentTask = task("new-lease");

        when(outboxMapper.selectByIdForUpdate(11L)).thenReturn(currentTask);

        assertNull(service.loadDiaryForDispatch(claimedTask));
        verify(diaryMapper, never()).selectByIdForUpdate(22L);
    }

    @Test
    void doesNotLoadDiaryWhenTheTaskIsNoLongerProcessing() {
        EmotionAnalysisOutboxMapper outboxMapper = mock(EmotionAnalysisOutboxMapper.class);
        EmotionDiaryMapper diaryMapper = mock(EmotionDiaryMapper.class);
        EmotionAnalysisOutboxService service = new EmotionAnalysisOutboxService(outboxMapper, diaryMapper);
        EmotionAnalysisOutbox claimedTask = task("lease-current");
        EmotionAnalysisOutbox currentTask = task("lease-current");
        currentTask.setStatus("PENDING");

        when(outboxMapper.selectByIdForUpdate(11L)).thenReturn(currentTask);

        assertNull(service.loadDiaryForDispatch(claimedTask));
        verify(diaryMapper, never()).selectByIdForUpdate(22L);
    }

    private EmotionAnalysisOutbox task(String leaseToken) {
        EmotionAnalysisOutbox task = new EmotionAnalysisOutbox();
        task.setId(11L);
        task.setDiaryId(22L);
        task.setStatus("PROCESSING");
        task.setLeaseToken(leaseToken);
        return task;
    }
}
