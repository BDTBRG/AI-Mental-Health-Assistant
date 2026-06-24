package com.amha.service.impl;

import cn.hutool.core.util.StrUtil;
import com.amha.common.BusinessException;
import com.amha.common.PageResult;
import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.dto.EmotionDiaryRequest;
import com.amha.dto.EmotionDiaryVO;
import com.amha.entity.EmotionDiary;
import com.amha.entity.User;
import com.amha.mapper.EmotionDiaryMapper;
import com.amha.mapper.UserMapper;
import com.amha.service.EmotionDiaryService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EmotionDiaryServiceImpl implements EmotionDiaryService {

    private final EmotionDiaryMapper diaryMapper;
    private final UserMapper userMapper;

    @Override
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
    }

    @Override
    public PageResult<EmotionDiaryVO> getAdminPage(EmotionDiaryPageQuery query) {
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
        if (StrUtil.isNotBlank(query.getUserId())) {
            wrapper.eq(EmotionDiary::getUserId, Long.parseLong(query.getUserId()));
        }
        wrapper.orderByDesc(EmotionDiary::getCreatedAt);

        Page<EmotionDiary> page = new Page<>(query.getCurrentPage(), query.getSize());
        IPage<EmotionDiary> result = diaryMapper.selectPage(page, wrapper);

        List<EmotionDiaryVO> records = result.getRecords().stream().map(diary -> {
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

        return PageResult.of(records, result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Override
    public void deleteAdmin(Long id) {
        EmotionDiary diary = diaryMapper.selectById(id);
        if (diary == null) {
            throw new BusinessException("日记不存在");
        }
        diaryMapper.deleteById(id);
    }
}
