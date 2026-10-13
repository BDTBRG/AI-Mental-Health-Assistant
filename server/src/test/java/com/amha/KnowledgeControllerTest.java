package com.amha;

import com.amha.common.Result;
import com.amha.dto.*;
import com.amha.entity.KnowledgeCategory;
import com.amha.mapper.KnowledgeCategoryMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KnowledgeCategoryMapper categoryMapper;

    private static String token;
    private static Long articleId;
    private static Long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        if (token == null) {
            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("admin");
            loginReq.setPassword("admin123");

            String resp = mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andReturn().getResponse().getContentAsString();
            Result<?> result = objectMapper.readValue(resp, Result.class);
            var dataMap = (Map<String, Object>) result.getData();
            token = (String) dataMap.get("token");
        }
        if (categoryId == null) {
            // 获取或创建分类
            KnowledgeCategory cat = new KnowledgeCategory();
            cat.setCategoryName("测试分类");
            cat.setDescription("测试用分类");
            cat.setSortOrder(99);
            cat.setStatus(1);
            categoryMapper.insert(cat);
            categoryId = cat.getId();
        }
    }

    @Test
    @Order(1)
    @DisplayName("获取分类树")
    void testGetCategoryTree() throws Exception {
        mockMvc.perform(get("/knowledge/category/tree")
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(2)
    @DisplayName("创建文章")
    void testCreateArticle() throws Exception {
        ArticleSaveRequest request = new ArticleSaveRequest();
        request.setTitle("测试文章标题");
        request.setContent("<p>测试内容</p>");
        request.setSummary("测试摘要");
        request.setCategoryId(categoryId);
        request.setTags("测试,示例");
        request.setCoverImage("");

        String resp = mockMvc.perform(post("/knowledge/article")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn().getResponse().getContentAsString();

        // 查询文章列表获取ID
        String listResp = mockMvc.perform(get("/knowledge/article/page")
                        .header("token", token)
                        .param("currentPage", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Result<?> result = objectMapper.readValue(listResp, Result.class);
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        if (!records.isEmpty()) {
            articleId = ((Number) records.get(0).get("id")).longValue();
        }
    }

    @Test
    @Order(3)
    @DisplayName("分页查询文章列表")
    void testGetArticlePage() throws Exception {
        mockMvc.perform(get("/knowledge/article/page")
                        .header("token", token)
                        .param("currentPage", "1")
                        .param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    @Order(4)
    @DisplayName("获取文章详情")
    void testGetArticleById() throws Exception {
        Assertions.assertNotNull(articleId, "文章ID不能为空");
        mockMvc.perform(get("/knowledge/article/" + articleId)
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.title").value("测试文章标题"));
    }

    @Test
    @Order(4)
    @DisplayName("未登录用户可读取文章详情")
    void testPublicArticleDetailDoesNotRequireLogin() throws Exception {
        Assertions.assertNotNull(articleId, "文章ID不能为空");
        mockMvc.perform(get("/knowledge/article/" + articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not("200")));
        mockMvc.perform(get("/knowledge/article/page")
                        .param("status", "0")
                        .param("currentPage", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == " + articleId + ")]").isEmpty());
    }

    @Test
    @Order(5)
    @DisplayName("更新文章")
    void testUpdateArticle() throws Exception {
        Assertions.assertNotNull(articleId, "文章ID不能为空");
        ArticleSaveRequest request = new ArticleSaveRequest();
        request.setTitle("更新后的文章标题");
        request.setContent("<p>更新后的内容</p>");
        request.setSummary("更新后的摘要");
        request.setCategoryId(categoryId);
        request.setTags("更新,测试");

        mockMvc.perform(put("/knowledge/article/" + articleId)
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(6)
    @DisplayName("修改文章状态")
    void testChangeArticleStatus() throws Exception {
        Assertions.assertNotNull(articleId, "文章ID不能为空");
        ArticleStatusRequest request = new ArticleStatusRequest();
        request.setStatus(1);

        mockMvc.perform(put("/knowledge/article/" + articleId + "/status")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(get("/knowledge/article/" + articleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(7)
    @DisplayName("删除文章")
    void testDeleteArticle() throws Exception {
        Assertions.assertNotNull(articleId, "文章ID不能为空");
        mockMvc.perform(delete("/knowledge/article/" + articleId)
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(8)
    @DisplayName("文件上传")
    void testFileUpload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", validPng());

        mockMvc.perform(multipart("/file/upload")
                        .file(file)
                        .param("businessType", "ARTICLE")
                        .param("businessId", "1")
                        .param("businessField", "cover")
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.filePath").isNotEmpty());
    }

    private static byte[] validPng() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
                (byte) 0xde, 0x00, 0x00, 0x00, 0x0b, 0x49, 0x44, 0x41,
                0x54, 0x78, (byte) 0x9c, 0x63, 0x60, 0x00, 0x02, 0x00,
                0x00, 0x05, 0x00, 0x01, (byte) 0xa5, (byte) 0xf6, 0x45,
                0x40, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
                (byte) 0xae, 0x42, 0x60, (byte) 0x82
        };
    }

    @Test
    @Order(9)
    @DisplayName("未登录用户不能修改知识文章")
    void testArticleWriteRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/knowledge/article/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("-1"));
    }
}
