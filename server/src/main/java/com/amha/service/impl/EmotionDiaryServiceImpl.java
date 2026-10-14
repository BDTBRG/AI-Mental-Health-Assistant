package com.amha.service.impl;

import cn.hutool.core.util.StrUtil;
import com.amha.common.BusinessException;
import com.amha.common.PageResult;
import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.dto.EmotionDiaryRequest;
import com.amha.dto.EmotionDiaryVO;
import com.amha.entity.EmotionAnalysisOutbox;
import com.amha.entity.EmotionDiaryAccessAudit;
import com.amha.entity.EmotionDiary;
import com.amha.entity.User;
import com.amha.mapper.EmotionDiaryMapper;
import com.amha.mapper.EmotionAnalysisOutboxMapper;
import com.amha.mapper.EmotionDiaryAccessAuditMapper;
import com.amha.mapper.UserMapper;
import com.amha.service.EmotionDiaryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmotionDiaryServiceImpl implements EmotionDiaryService {

    private final EmotionDiaryMapper diaryMapper;
    private final UserMapper userMapper;
    private final EmotionAnalysisOutboxMapper outboxMapper;
    private final EmotionDiaryAccessAuditMapper auditMapper;

    @Override
    @Transactional
    public void submitDiary(EmotionDiaryRequest request, Long userId) {
        EmotionDiary diary = new EmotionDiary();
        diary.setUserId(userId);
        diary.setDiaryDate(LocalDate.parse(request.getDiaryDate()));
        diary.setMoodScore(request.getMoodScore());
        diary.setDominantEmotion(request.getDominantEmotion());
        diary.setEmotionTriggers(request.getEmotionTriggers());
        diary.setDiaryContent(request.getDiaryContent());
        diary.setSleepQuality(request.getSleepQuality());
        diary.setStressLevel(request.getStressLevel());
        diary.setHasAiEmotionAnalysis(0);
        diary.setAiAnalysisStatus("PENDING");
        diaryMapper.insert(diary);

        com.amha.entity.EmotionAnalysisOutbox task = new com.amha.entity.EmotionAnalysisOutbox();
        task.setDiaryId(diary.getId());
        task.setStatus("PENDING");
        task.setAttempts(0);
        task.setNextAttemptAt(LocalDateTime.now());
        outboxMapper.insert(task);
    }

    @Override
    @Transactional
    public PageResult<EmotionDiaryVO> getAdminPage(EmotionDiaryPageQuery query, Long actorUserId) {
        LambdaQueryWrapper<EmotionDiary> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(query.getDominantEmotion())) {
            wrapper.eq(EmotionDiary::getDominantEmotion, query.getDominantEmotion());
        }
        if (query.getMinMoodScore() != null) {
            wrapper.ge(EmotionDiary::getMoodScore, query.getMinMoodScore());
        }
        if (query.getMaxMoodScore() != null) {
            wrapper.le(EmotionDiary::getMoodScore, query.getMaxMoodScore());
        }
        if (query.getUserId() != null) {
            wrapper.eq(EmotionDiary::getUserId, query.getUserId());
        }
        wrapper.orderByDesc(EmotionDiary::getCreatedAt)
                .orderByDesc(EmotionDiary::getId);

        Page<EmotionDiary> page = new Page<>(query.getCurrentPage(), query.getSize());
        IPage<EmotionDiary> result = diaryMapper.selectPage(page, wrapper);
        result.getRecords().forEach(diary -> recordAccess(diary.getId(), actorUserId, "ADMIN_READ"));
        List<EmotionDiaryVO> records = convertToVOList(result.getRecords());
        return PageResult.of(records, result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Override
    @Transactional
    public void deleteAdmin(Long id, Long actorUserId) {
        EmotionDiary diary = diaryMapper.selectById(id);
        if (diary == null) {
            throw new BusinessException("日记不存在");
        }
        recordAccess(id, actorUserId, "ADMIN_DELETE");
        outboxMapper.delete(new LambdaQueryWrapper<EmotionAnalysisOutbox>()
                .eq(EmotionAnalysisOutbox::getDiaryId, id));
        diaryMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteOwnDiary(Long id, Long ownerUserId) {
        EmotionDiary diary = diaryMapper.selectOne(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getId, id)
                .eq(EmotionDiary::getUserId, ownerUserId));
        if (diary == null) {
            throw new BusinessException("404", "日记不存在");
        }
        recordAccess(id, ownerUserId, "OWNER_DELETE");
        outboxMapper.delete(new LambdaQueryWrapper<EmotionAnalysisOutbox>()
                .eq(EmotionAnalysisOutbox::getDiaryId, id));
        diaryMapper.deleteById(id);
    }

    @Override
    public List<EmotionDiaryVO> getUserDiaries(Long userId) {
        LambdaQueryWrapper<EmotionDiary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmotionDiary::getUserId, userId);
        wrapper.orderByDesc(EmotionDiary::getCreatedAt);
        wrapper.last("LIMIT 30");
        List<EmotionDiary> diaries = diaryMapper.selectList(wrapper);
        return convertToVOList(diaries);
    }

    @Override
    public void triggerPendingAnalysis() {
        LambdaQueryWrapper<EmotionDiary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(EmotionDiary::getAiAnalysisStatus, "PENDING");
        wrapper.last("LIMIT 20");
        List<EmotionDiary> pending = diaryMapper.selectList(wrapper);
        log.info("Triggering analysis for {} pending diaries", pending.size());
        for (EmotionDiary diary : pending) {
            Long taskCount = outboxMapper.selectCount(new LambdaQueryWrapper<EmotionAnalysisOutbox>()
                    .eq(EmotionAnalysisOutbox::getDiaryId, diary.getId()));
            if (taskCount == null || taskCount == 0) {
                EmotionAnalysisOutbox task = new EmotionAnalysisOutbox();
                task.setDiaryId(diary.getId());
                task.setStatus("PENDING");
                task.setAttempts(0);
                task.setNextAttemptAt(LocalDateTime.now());
                outboxMapper.insert(task);
            }
        }
    }

    private List<EmotionDiaryVO> convertToVOList(List<EmotionDiary> diaries) {
        return diaries.stream().map(diary -> {
            User user = userMapper.selectById(diary.getUserId());
            return EmotionDiaryVO.builder()
                    .id(diary.getId())
                    .userId(diary.getUserId())
                    .username(user != null ? user.getUsername() : "")
                    .nickname(user != null ? user.getNickname() : "")
                    .diaryDate(diary.getDiaryDate())
                    .moodScore(diary.getMoodScore())
                    .dominantEmotion(diary.getDominantEmotion())
                    .emotionTriggers(diary.getEmotionTriggers())
                    .diaryContent(diary.getDiaryContent())
                    .diaryContentPreview(diary.getDiaryContent() != null && diary.getDiaryContent().length() > 100
                            ? diary.getDiaryContent().substring(0, 100) + "..."
                            : diary.getDiaryContent())
                    .sleepQuality(diary.getSleepQuality())
                    .stressLevel(diary.getStressLevel())
                    .aiEmotionAnalysis(diary.getAiEmotionAnalysis())
                    .aiAnalysisUpdatedAt(diary.getAiAnalysisUpdatedAt())
                    .hasAiEmotionAnalysis(diary.getHasAiEmotionAnalysis() == 1)
                    .aiAnalysisStatus(diary.getAiAnalysisStatus())
                    .contentLength(diary.getDiaryContent() != null ? diary.getDiaryContent().length() : 0)
                    .createdAt(diary.getCreatedAt())
                    .updatedAt(diary.getUpdatedAt())
                    .build();
        }).collect(Collectors.toList());
    }

    private void recordAccess(Long diaryId, Long actorUserId, String action) {
        EmotionDiaryAccessAudit audit = new EmotionDiaryAccessAudit();
        audit.setDiaryId(diaryId);
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        audit.setCreatedAt(LocalDateTime.now());
        auditMapper.insert(audit);
    }
}
