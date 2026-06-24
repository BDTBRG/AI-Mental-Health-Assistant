package com.amha.service;

import com.amha.common.PageResult;
import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.dto.EmotionDiaryRequest;
import com.amha.dto.EmotionDiaryVO;

public interface EmotionDiaryService {
    void submitDiary(EmotionDiaryRequest request, Long userId);
    PageResult<EmotionDiaryVO> getAdminPage(EmotionDiaryPageQuery query);
    void deleteAdmin(Long id);
}
