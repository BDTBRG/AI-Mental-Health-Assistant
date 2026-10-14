package com.amha.controller;

import com.amha.common.PageResult;
import com.amha.common.Result;
import com.amha.common.BusinessException;
import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.dto.EmotionDiaryRequest;
import com.amha.dto.EmotionDiaryVO;
import com.amha.service.EmotionDiaryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /**
     * 用户端：获取自己的日记列表（含AI分析结果）
     */
    @GetMapping("/my")
    public Result<List<EmotionDiaryVO>> getMyDiaries(HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        return Result.success(emotionDiaryService.getUserDiaries(userId));
    }

    // ---- 管理端 ----

    @GetMapping("/admin/page")
    public Result<PageResult<EmotionDiaryVO>> getAdminPage(@Valid @ModelAttribute EmotionDiaryPageQuery query,
                                                            HttpServletRequest request) {
        Long actorUserId = requireAdmin(request);
        return Result.success(emotionDiaryService.getAdminPage(query, actorUserId));
    }

    @DeleteMapping("/admin/{id}")
    public Result<Void> deleteAdmin(@PathVariable Long id, HttpServletRequest request) {
        Long actorUserId = requireAdmin(request);
        emotionDiaryService.deleteAdmin(id, actorUserId);
        return Result.success();
    }

    @DeleteMapping("/my/{id}")
    public Result<Void> deleteMyDiary(@PathVariable Long id, HttpServletRequest request) {
        Long ownerUserId = (Long) request.getAttribute("userId");
        emotionDiaryService.deleteOwnDiary(id, ownerUserId);
        return Result.success();
    }

    private Long requireAdmin(HttpServletRequest request) {
        Integer userType = (Integer) request.getAttribute("userType");
        Long actorUserId = (Long) request.getAttribute("userId");
        if (!Integer.valueOf(2).equals(userType) || actorUserId == null) {
            throw new BusinessException("403", "仅管理员可访问情绪日记管理接口");
        }
        return actorUserId;
    }
}
