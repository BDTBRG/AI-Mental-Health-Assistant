package com.amha.service;

import com.amha.dto.ArticleDetailVO;
import com.amha.dto.ArticlePageQuery;
import com.amha.dto.ArticleSaveRequest;
import com.amha.dto.ArticleVO;
import com.amha.dto.CategoryVO;
import com.amha.common.PageResult;

import java.util.List;
import java.util.Map;

public interface KnowledgeService {
    List<CategoryVO> getCategoryTree();
    PageResult<ArticleVO> getArticlePage(ArticlePageQuery query);
    PageResult<ArticleVO> getArticlePage(ArticlePageQuery query, Integer userType);
    void createArticle(ArticleSaveRequest request, Long userId, String username);
    ArticleDetailVO getArticleById(Long id);
    ArticleDetailVO getArticleById(Long id, Integer userType);
    void updateArticle(Long id, ArticleSaveRequest request);
    void changeArticleStatus(Long id, Integer status);
    void deleteArticle(Long id);
    Map<String, Object> rebuildPublishedKnowledgeIndex();
}
