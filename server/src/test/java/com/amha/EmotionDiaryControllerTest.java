package com.amha;

import com.amha.common.Result;
import com.amha.dto.EmotionDiaryRequest;
import com.amha.dto.LoginRequest;
import com.amha.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmotionDiaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String userToken;
    private static String adminToken;

    @BeforeAll
    static void setup() {
        // static fields will be initialized in @BeforeEach
    }

    @BeforeEach
    void setUp() throws Exception {
        if (userToken == null) {
            // 注册普通用户
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("diaryuser");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("diary@test.com");
            regReq.setGender(1);
            regReq.setUserType(1);
            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("diaryuser");
            loginReq.setPassword("test123");
            String resp = mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andReturn().getResponse().getContentAsString();
            Result<?> result = objectMapper.readValue(resp, Result.class);
            var dataMap = (Map<String, Object>) result.getData();
            userToken = (String) dataMap.get("token");
        }
        if (adminToken == null) {
            // 注册管理员
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("diaryadmin");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("diaryadmin@test.com");
            regReq.setGender(1);
            regReq.setUserType(2);
            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("diaryadmin");
            loginReq.setPassword("test123");
            String resp = mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andReturn().getResponse().getContentAsString();
            Result<?> result = objectMapper.readValue(resp, Result.class);
            var dataMap = (Map<String, Object>) result.getData();
            adminToken = (String) dataMap.get("token");
        }
    }

    @Test
    @Order(1)
    @DisplayName("提交情绪日记")
    void testSubmitDiary() throws Exception {
        EmotionDiaryRequest request = new EmotionDiaryRequest();
        request.setDiaryContent("今天心情不错，完成了所有计划的任务。");
        request.setDiaryDate("2026-06-24");
        request.setDominantEmotion("开心");
        request.setEmotionTriggers("工作效率高");
        request.setMoodScore(8);
        request.setSleepQuality(4);
        request.setStressLevel(2);

        mockMvc.perform(post("/emotion-diary")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(2)
    @DisplayName("管理员分页查询情绪日记")
    void testAdminGetPage() throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .header("token", adminToken)
                        .param("currentPage", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber());
    }

    @Test
    @Order(3)
    @DisplayName("管理员按情绪筛选")
    void testAdminFilterByEmotion() throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .header("token", adminToken)
                        .param("currentPage", "1")
                        .param("size", "10")
                        .param("dominantEmotion", "开心"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(4)
    @DisplayName("管理员删除情绪日记")
    void testAdminDelete() throws Exception {
        // 先获取日记列表拿到ID
        String resp = mockMvc.perform(get("/emotion-diary/admin/page")
                        .header("token", adminToken)
                        .param("currentPage", "1")
                        .param("size", "1"))
                .andReturn().getResponse().getContentAsString();
        Result<?> result = objectMapper.readValue(resp, Result.class);
        Map<String, Object> data = (Map<String, Object>) result.getData();
        var records = (java.util.List<Map<String, Object>>) data.get("records");
        if (!records.isEmpty()) {
            Long diaryId = ((Number) records.get(0).get("id")).longValue();
            mockMvc.perform(delete("/emotion-diary/admin/" + diaryId)
                            .header("token", adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("200"));
        }
    }
}
