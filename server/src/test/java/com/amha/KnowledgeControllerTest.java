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
            // 注册并登录
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("knowtest");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("knowtest@test.com");
            regReq.setGender(1);
            regReq.setUserType(2);

            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("knowtest");
            loginReq.setPassword("test123");

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
                "file", "test.png", "image/png", "test image content".getBytes());

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
}
