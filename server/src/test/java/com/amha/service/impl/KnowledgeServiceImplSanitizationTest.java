package com.amha.service.impl;

import com.amha.common.BusinessException;
import com.amha.agent.RealChatAgent;
import com.amha.dto.ArticleDetailVO;
import com.amha.dto.ArticleSaveRequest;
import com.amha.entity.KnowledgeArticle;
import com.amha.mapper.KnowledgeArticleMapper;
import com.amha.mapper.KnowledgeCategoryMapper;
import com.amha.security.ArticleHtmlSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyList;

class KnowledgeServiceImplSanitizationTest {
    private KnowledgeCategoryMapper categoryMapper;
    private KnowledgeArticleMapper articleMapper;
    private KnowledgeIndexOutboxService indexOutboxService;
    private RealChatAgent realChatAgent;
    private KnowledgeServiceImpl service;

    @BeforeEach
    void setUp() {
        categoryMapper = mock(KnowledgeCategoryMapper.class);
        articleMapper = mock(KnowledgeArticleMapper.class);
        indexOutboxService = mock(KnowledgeIndexOutboxService.class);
        realChatAgent = mock(RealChatAgent.class);
        service = new KnowledgeServiceImpl(
                categoryMapper,
                articleMapper,
                indexOutboxService,
                new ArticleHtmlSanitizer(),
                realChatAgent);
    }

    @Test
    void createArticleStoresSanitizedContentAndKeepsSafeFormatting() {
        ArticleSaveRequest request = requestWithUnsafeContent();

        service.createArticle(request, 7L, "admin");

        ArgumentCaptor<KnowledgeArticle> article = ArgumentCaptor.forClass(KnowledgeArticle.class);
        verify(articleMapper).insert(article.capture());
        String stored = article.getValue().getContent();
        assertTrue(stored.contains("<p>可读内容</p>"));
        assertTrue(stored.contains("<strong>重点</strong>"));
        assertTrue(stored.contains("color"));
        assertFalse(stored.contains("background-image"));
        assertFalse(stored.contains("<script"));
        assertFalse(stored.contains("onerror"));
        assertFalse(stored.contains("javascript:"));
    }

    @Test
    void updateArticleSanitizesContentBeforeSavingAndIndexing() {
        KnowledgeArticle existing = new KnowledgeArticle();
        existing.setId(11L);
        existing.setStatus(1);
        when(articleMapper.selectById(11L)).thenReturn(existing);

        service.updateArticle(11L, requestWithUnsafeContent());

        ArgumentCaptor<KnowledgeArticle> article = ArgumentCaptor.forClass(KnowledgeArticle.class);
        verify(articleMapper).updateById(article.capture());
        assertFalse(article.getValue().getContent().contains("onerror"));
        assertFalse(article.getValue().getContent().contains("javascript:"));
        verify(indexOutboxService).enqueueArticle(11L);
    }

    @Test
    void getArticleSanitizesPreviouslyStoredContentBeforeReturningIt() {
        KnowledgeArticle legacy = new KnowledgeArticle();
        legacy.setId(12L);
        legacy.setCategoryId(3L);
        legacy.setStatus(1);
        legacy.setContent(requestWithUnsafeContent().getContent());
        when(articleMapper.selectById(12L)).thenReturn(legacy);

        ArticleDetailVO result = service.getArticleById(12L, 1);

        assertTrue(result.getContent().contains("<p>可读内容</p>"));
        assertFalse(result.getContent().contains("<script"));
        assertFalse(result.getContent().contains("onerror"));
        assertFalse(result.getContent().contains("javascript:"));
    }

    @Test
    void changeArticleStatusRejectsUnsupportedValuesBeforeTouchingArticleOrIndex() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.changeArticleStatus(11L, 99));

        assertEquals("400", error.getCode());
        verifyNoInteractions(articleMapper, indexOutboxService);
    }

    @Test
    void rebuildPublishedKnowledgeIndexUsesOnlyPublishedArticlesAndReturnsAgentValidation() {
        KnowledgeArticle published = new KnowledgeArticle();
        published.setId(21L);
        published.setTitle("已发布内容");
        published.setContent("<p>可检索的心理知识</p><script>危险脚本</script>");
        published.setStatus(1);
        when(articleMapper.selectPublishedForIndex()).thenReturn(List.of(published));
        Map<String, Object> agentResult = Map.of(
                "total_vectors", 2,
                "total_articles", 1,
                "index_version", "validated-version");
        when(realChatAgent.rebuildKnowledgeIndex(anyList())).thenReturn(agentResult);

        Map<String, Object> result = service.rebuildPublishedKnowledgeIndex();

        assertEquals(agentResult, result);
        verify(articleMapper).selectPublishedForIndex();

        ArgumentCaptor<List<Map<String, Object>>> documents = ArgumentCaptor.forClass(List.class);
        verify(realChatAgent).rebuildKnowledgeIndex(documents.capture());
        assertEquals(1, documents.getValue().size());
        assertEquals(21L, documents.getValue().get(0).get("id"));
        assertEquals("已发布内容", documents.getValue().get(0).get("title"));
        assertFalse(String.valueOf(documents.getValue().get(0).get("content")).contains("<script"));
        assertFalse(String.valueOf(documents.getValue().get(0).get("content")).contains("危险脚本"));
    }

    private ArticleSaveRequest requestWithUnsafeContent() {
        ArticleSaveRequest request = new ArticleSaveRequest();
        request.setCategoryId(3L);
        request.setTitle("安全格式文章");
        request.setContent(
                "<p>可读内容</p><script>alert(1)</script><p><strong>重点</strong></p>"
                        + "<span style=\"color:#ff0000;font-size:16px;background-image:url(javascript:alert(4))\">颜色</span>"
                        + "<img src=x onerror=alert(2)><a href=\"javascript:alert(3)\">链接</a>");
        return request;
    }
}
