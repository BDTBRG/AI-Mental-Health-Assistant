package com.amha;

import com.amha.common.Result;
import com.amha.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String userToken;
    private static String adminToken;
    private static Long sessionId;

    @BeforeEach
    void setUp() throws Exception {
        if (userToken == null) {
            // 注册用户
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("chatuser");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("chat@test.com");
            regReq.setGender(1);
            regReq.setUserType(1);
            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("chatuser");
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
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("chatadmin");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("chatadmin@test.com");
            regReq.setGender(1);
            regReq.setUserType(2);
            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("chatadmin");
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
    @DisplayName("创建咨询会话")
    void testStartSession() throws Exception {
        StartSessionRequest request = new StartSessionRequest();
        request.setInitialMessage("我最近感觉有些焦虑");
        request.setSessionTitle("焦虑咨询-" + System.currentTimeMillis());

        MvcResult result = mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.sessionId").isNumber())
                .andReturn();

        String resp = result.getResponse().getContentAsString();
        Result<?> resultObj = objectMapper.readValue(resp, Result.class);
        var dataMap = (Map<String, Object>) resultObj.getData();
        sessionId = ((Number) dataMap.get("sessionId")).longValue();
    }

    @Test
    @Order(2)
    @DisplayName("查询用户会话列表")
    void testGetUserSessions() throws Exception {
        mockMvc.perform(get("/psychological-chat/sessions")
                        .header("token", userToken)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @Order(3)
    @DisplayName("管理员查询所有会话")
    void testAdminGetAllSessions() throws Exception {
        mockMvc.perform(get("/psychological-chat/sessions")
                        .header("token", adminToken)
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(4)
    @DisplayName("SSE流式对话")
    void testStreamChat() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");

        ChatStreamRequest request = new ChatStreamRequest();
        request.setSessionId(sessionId.toString());
        request.setUserMessage("我感到很焦虑，不知道该怎么办");

        // SSE请求验证返回content-type
        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk());
    }

    @Test
    @Order(5)
    @DisplayName("获取会话消息")
    void testGetSessionMessages() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");
        mockMvc.perform(get("/psychological-chat/sessions/" + sessionId + "/messages")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @Order(6)
    @DisplayName("获取会话情绪分析")
    void testGetSessionEmotion() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");
        mockMvc.perform(get("/psychological-chat/session/" + sessionId + "/emotion")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.primaryEmotion").isString());
    }

    @Test
    @Order(7)
    @DisplayName("删除会话")
    void testDeleteSession() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");
        mockMvc.perform(delete("/psychological-chat/sessions/" + sessionId)
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }
}
