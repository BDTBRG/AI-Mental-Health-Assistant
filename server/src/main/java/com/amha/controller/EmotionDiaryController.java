package com.amha.controller;

import com.amha.common.PageResult;
import com.amha.common.Result;
import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.dto.EmotionDiaryRequest;
import com.amha.dto.EmotionDiaryVO;
import com.amha.service.EmotionDiaryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/emotion-diary")
@RequiredArgsConstructor
public class EmotionDiaryController {

    private final EmotionDiaryService emotionDiaryService;

    @PostMapping
    public Result<Void> submitDiary(@Valid @RequestBody EmotionDiaryRequest request,
                                    HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        emotionDiaryService.submitDiary(request, userId);
        return Result.success();
    }

    @GetMapping("/admin/page")
    public Result<PageResult<EmotionDiaryVO>> getAdminPage(EmotionDiaryPageQuery query) {
        return Result.success(emotionDiaryService.getAdminPage(query));
    }

    @DeleteMapping("/admin/{id}")
    public Result<Void> deleteAdmin(@PathVariable Long id) {
        emotionDiaryService.deleteAdmin(id);
        return Result.success();
    }
}
