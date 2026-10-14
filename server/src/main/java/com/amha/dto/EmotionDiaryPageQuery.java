package com.amha.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;

@Data
public class EmotionDiaryPageQuery {
    @NotNull(message = "当前页不能为空")
    @Min(value = 1, message = "当前页必须大于等于1")
    private Integer currentPage = 1;
    @NotNull(message = "每页条数不能为空")
    @Min(value = 1, message = "每页条数必须大于等于1")
    @Max(value = 100, message = "每页条数不能超过100")
    private Integer size = 10;
    private String dominantEmotion;
    @Min(value = 1, message = "情绪评分必须大于等于1")
    @Max(value = 10, message = "情绪评分不能超过10")
    private Integer minMoodScore;
    @Min(value = 1, message = "情绪评分必须大于等于1")
    @Max(value = 10, message = "情绪评分不能超过10")
    private Integer maxMoodScore;
    @Positive(message = "用户ID必须为正整数")
    private Long userId;

    @AssertTrue(message = "情绪评分最小值不能大于最大值")
    public boolean isMoodScoreRangeValid() {
        return minMoodScore == null || maxMoodScore == null || minMoodScore <= maxMoodScore;
    }
}
