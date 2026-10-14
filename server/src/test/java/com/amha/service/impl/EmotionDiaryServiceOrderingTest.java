package com.amha.service.impl;

import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.entity.EmotionDiary;
import com.amha.mapper.EmotionAnalysisOutboxMapper;
import com.amha.mapper.EmotionDiaryAccessAuditMapper;
import com.amha.mapper.EmotionDiaryMapper;
import com.amha.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmotionDiaryServiceOrderingTest {

    @Test
    @SuppressWarnings("unchecked")
    void adminDiaryPageUsesIdAsStableTieBreakerForEqualCreationTimes() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, EmotionDiary.class);

        EmotionDiaryMapper diaryMapper = mock(EmotionDiaryMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        EmotionAnalysisOutboxMapper outboxMapper = mock(EmotionAnalysisOutboxMapper.class);
        EmotionDiaryAccessAuditMapper auditMapper = mock(EmotionDiaryAccessAuditMapper.class);
        Page<EmotionDiary> page = new Page<>(1, 10);
        when(diaryMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        EmotionDiaryServiceImpl service = new EmotionDiaryServiceImpl(
                diaryMapper, userMapper, outboxMapper, auditMapper);
        service.getAdminPage(new EmotionDiaryPageQuery(), 99L);

        ArgumentCaptor<Wrapper<EmotionDiary>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(diaryMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        String orderBy = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(orderBy.contains("created_at DESC"), orderBy);
        assertTrue(orderBy.contains("id DESC"), orderBy);
    }
}
