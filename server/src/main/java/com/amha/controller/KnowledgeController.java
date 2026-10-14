package com.amha.controller;

import com.amha.common.PageResult;
import com.amha.common.Result;
import com.amha.dto.*;
import com.amha.service.KnowledgeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/category/tree")
    public Result<List<CategoryVO>> getCategoryTree() {
        return Result.success(knowledgeService.getCategoryTree());
    }

    @GetMapping("/article/page")
    public Result<PageResult<ArticleVO>> getArticlePage(ArticlePageQuery query,
                                                       HttpServletRequest httpRequest) {
        Integer userType = (Integer) httpRequest.getAttribute("userType");
        return Result.success(knowledgeService.getArticlePage(query, userType));
    }

    @PostMapping("/article")
    public Result<Void> createArticle(@RequestBody ArticleSaveRequest request,
                                      HttpServletRequest httpRequest) {
        Long userId = (Long) httpRequest.getAttribute("userId");
        String username = (String) httpRequest.getAttribute("username");
        knowledgeService.createArticle(request, userId, username);
        return Result.success();
    }

    @GetMapping("/article/{id}")
    public Result<ArticleDetailVO> getArticleById(@PathVariable Long id,
                                                  HttpServletRequest httpRequest) {
        Integer userType = (Integer) httpRequest.getAttribute("userType");
        return Result.success(knowledgeService.getArticleById(id, userType));
    }

    @PutMapping("/article/{id}")
    public Result<Void> updateArticle(@PathVariable Long id,
                                      @RequestBody ArticleSaveRequest request) {
        knowledgeService.updateArticle(id, request);
        return Result.success();
    }

    @PutMapping("/article/{id}/status")
    public Result<Void> changeArticleStatus(@PathVariable Long id,
                                            @RequestBody ArticleStatusRequest request) {
        knowledgeService.changeArticleStatus(id, request.getStatus());
        return Result.success();
    }

    @DeleteMapping("/article/{id}")
    public Result<Void> deleteArticle(@PathVariable Long id) {
        knowledgeService.deleteArticle(id);
        return Result.success();
    }

    @PostMapping("/index/rebuild")
    public Result<Map<String, Object>> rebuildPublishedKnowledgeIndex() {
        return Result.success(knowledgeService.rebuildPublishedKnowledgeIndex());
    }
}
