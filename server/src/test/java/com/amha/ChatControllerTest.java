package com.amha;

import com.amha.common.Result;
import com.amha.agent.RealChatAgent;
import com.amha.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.mockito.ArgumentCaptor;
import com.amha.service.impl.EmotionAnalysisOutboxService;
import com.amha.service.impl.ChatTurnStore;
import com.amha.service.impl.KnowledgeIndexOutboxService;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.outbox.enabled=false")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EmotionAnalysisOutboxService outboxService;

    @Autowired
    private ChatTurnStore chatTurnStore;

    @Autowired
    private KnowledgeIndexOutboxService knowledgeIndexOutboxService;

    @Autowired
    private com.amha.service.impl.AgentCheckpointPurgeOutboxService checkpointPurgeOutboxService;

    @MockBean(name = "realChatAgent")
    private RealChatAgent realChatAgent;

    private static String userToken;
    private static String otherUserToken;
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
            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("admin");
            loginReq.setPassword("admin123");
            String resp = mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andReturn().getResponse().getContentAsString();
            Result<?> result = objectMapper.readValue(resp, Result.class);
            var dataMap = (Map<String, Object>) result.getData();
            adminToken = (String) dataMap.get("token");
        }
        if (otherUserToken == null) {
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("chatother");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("chatother@test.com");
            regReq.setGender(1);
            regReq.setUserType(1);
            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("chatother");
            loginReq.setPassword("test123");
            String resp = mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andReturn().getResponse().getContentAsString();
            Result<?> result = objectMapper.readValue(resp, Result.class);
            var dataMap = (Map<String, Object>) result.getData();
            otherUserToken = (String) dataMap.get("token");
        }
    }

    private Long createSession(String token) throws Exception {
        StartSessionRequest request = new StartSessionRequest();
        request.setInitialMessage("用于权限回归测试");
        request.setSessionTitle("权限测试会话-" + System.nanoTime());
        String response = mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andReturn().getResponse().getContentAsString();
        Result<?> result = objectMapper.readValue(response, Result.class);
        Map<String, Object> data = (Map<String, Object>) result.getData();
        return ((Number) data.get("sessionId")).longValue();
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
    @DisplayName("他人不能读取或发起指定会话")
    void testOtherUserCannotAccessSession() throws Exception {
        Long protectedSessionId = createSession(userToken);

        mockMvc.perform(get("/psychological-chat/sessions/" + protectedSessionId + "/messages")
                        .header("token", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        mockMvc.perform(get("/psychological-chat/session/" + protectedSessionId + "/emotion")
                        .header("token", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        ChatStreamRequest request = new ChatStreamRequest();
        request.setSessionId(protectedSessionId.toString());
        request.setUserMessage("不应该进入这段会话");
        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        mockMvc.perform(put("/psychological-chat/sessions/" + protectedSessionId + "/review-consent")
                        .header("token", otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        mockMvc.perform(delete("/psychological-chat/sessions/" + protectedSessionId)
                        .header("token", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));
    }

    @Test
    @Order(5)
    @DisplayName("管理员仅能读取用户明确授权的会话，且用户可撤回授权")
    void testAdminReviewRequiresAndHonorsConsent() throws Exception {
        Long protectedSessionId = createSession(userToken);

        mockMvc.perform(get("/psychological-chat/sessions/" + protectedSessionId + "/messages")
                        .header("token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        mockMvc.perform(get("/psychological-chat/session/" + protectedSessionId + "/emotion")
                        .header("token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        mockMvc.perform(get("/psychological-chat/sessions")
                        .header("token", adminToken)
                        .param("pageNum", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == " + protectedSessionId + ")]").isEmpty());

        mockMvc.perform(put("/psychological-chat/sessions/" + protectedSessionId + "/review-consent")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(get("/psychological-chat/sessions/" + protectedSessionId + "/messages")
                        .header("token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        mockMvc.perform(get("/psychological-chat/sessions")
                        .header("token", adminToken)
                        .param("pageNum", "1")
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == " + protectedSessionId + ")]").isEmpty());

        mockMvc.perform(post("/psychological-chat/sessions/" + protectedSessionId + "/review-request")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(get("/psychological-chat/sessions/" + protectedSessionId + "/messages")
                        .header("token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(put("/psychological-chat/sessions/" + protectedSessionId + "/review-consent")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(get("/psychological-chat/sessions/" + protectedSessionId + "/messages")
                        .header("token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_review_audit WHERE session_id = ?",
                Integer.class, protectedSessionId);
        Assertions.assertEquals(5, auditCount, "授权、申请、复核读取和撤回都应留审计记录");
    }

    @Test
    @Order(6)
    @DisplayName("人工复核状态机支持领取、完成、并发冲突和授权撤回")
    void reviewStateMachineUsesOptimisticVersionAndAudit() throws Exception {
        Long reviewSessionId = createSession(userToken);
        mockMvc.perform(put("/psychological-chat/sessions/" + reviewSessionId + "/review-consent")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(post("/psychological-chat/sessions/" + reviewSessionId + "/review-request")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(get("/psychological-chat/sessions")
                        .header("token", adminToken)
                        .param("pageNum", "1")
                        .param("pageSize", "50")
                        .param("reviewStatus", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == " + reviewSessionId + ")]").isNotEmpty());

        String claimBody = "{\"targetStatus\":\"IN_PROGRESS\",\"expectedVersion\":1}";
        mockMvc.perform(put("/psychological-chat/admin/sessions/" + reviewSessionId + "/review-state")
                        .header("token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(put("/psychological-chat/admin/sessions/" + reviewSessionId + "/review-state")
                        .header("token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"RESOLVED\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"));

        mockMvc.perform(put("/psychological-chat/admin/sessions/" + reviewSessionId + "/review-state")
                        .header("token", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStatus\":\"RESOLVED\",\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(post("/psychological-chat/sessions/" + reviewSessionId + "/review-request")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(put("/psychological-chat/sessions/" + reviewSessionId + "/review-consent")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT review_status FROM consultation_session WHERE id = ?",
                String.class, reviewSessionId);
        Assertions.assertEquals("CANCELLED", finalStatus);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_review_audit WHERE session_id = ?",
                Integer.class, reviewSessionId);
        Assertions.assertEquals(7, auditCount);
    }

    @Test
    @Order(7)
    @DisplayName("重复提交同一轮流式请求不会重复保存用户消息")
    void duplicateStreamRequestIsIdempotent() throws Exception {
        Long testSessionId = createSession(userToken);
        String clientRequestId = "retry-" + System.nanoTime();
        String message = "网络断线恢复时不应重复保存";
        String body = "{\"sessionId\":\"" + testSessionId + "\","
                + "\"clientRequestId\":\"" + clientRequestId + "\","
                + "\"userMessage\":\"" + message + "\"}";

        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap()))
                .thenReturn(Flux.just("稳定回复"));
        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .header("Last-Event-ID", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk());

        awaitTurnCompletion(testSessionId, clientRequestId);

        Long contentEventId = jdbcTemplate.queryForObject(
                "SELECT MIN(id) FROM consultation_stream_event WHERE session_id = ? AND client_request_id = ? AND event_type = 'message'",
                Long.class, testSessionId, clientRequestId);
        MvcResult replay = mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .header("Last-Event-ID", String.valueOf(contentEventId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andReturn();
        String replayBody = replay.getResponse().getContentAsString();
        Assertions.assertTrue(replayBody.contains("event:done"), "断点后应重放完成事件");
        Assertions.assertFalse(replayBody.contains("event:message"), "断点前已确认的片段不应重复下发");

        Integer savedMessages = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                Integer.class, testSessionId, message);
        Assertions.assertEquals(1, savedMessages, "同一 clientRequestId 只能落库一条用户消息");
        Integer savedTurns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                Integer.class, testSessionId, clientRequestId);
        Assertions.assertEquals(1, savedTurns, "同一 clientRequestId 只能创建一个轮次");
        verify(realChatAgent, times(1)).streamResponse(anyLong(), anyString(), anyList(), anyMap());
        String turnStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                String.class, testSessionId, clientRequestId);
        Assertions.assertEquals("COMPLETED", turnStatus);
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_stream_event WHERE session_id = ? AND client_request_id = ?",
                Integer.class, testSessionId, clientRequestId);
        Assertions.assertEquals(2, eventCount, "内容片段和完成事件都应可用于重放");

        String conflictingBody = body.replace(message, "不同消息");
        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(conflictingBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    @Order(8)
    @DisplayName("SSE流式对话")
    void testStreamChat() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");
        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap()))
                .thenReturn(Flux.just("测试回复"));

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
        awaitNoRunningTurns(sessionId);
    }

    @Test
    @Order(9)
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
    @Order(10)
    @DisplayName("获取会话情绪分析")
    void testGetSessionEmotion() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");
        when(realChatAgent.analyzeEmotion(anyLong(), anyList(), anyLong())).thenReturn(Map.of(
                "primaryEmotion", "平静",
                "emotionScore", 60,
                "riskLevel", 0,
                "riskStatus", "known",
                "isNegative", false));
        mockMvc.perform(get("/psychological-chat/session/" + sessionId + "/emotion")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.primaryEmotion").isString());
    }

    @Test
    @Order(11)
    @DisplayName("删除会话")
    void testDeleteSession() throws Exception {
        Assertions.assertNotNull(sessionId, "会话ID不能为空");
        Integer turnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ?",
                Integer.class, sessionId);
        Assertions.assertTrue(turnCount > 0, "测试会话应包含流式轮次");
        mockMvc.perform(delete("/psychological-chat/sessions/" + sessionId)
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        Integer remainingTurns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ?",
                Integer.class, sessionId);
        Integer remainingEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_stream_event WHERE session_id = ?",
                Integer.class, sessionId);
        Integer purgeTasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_checkpoint_purge_outbox WHERE session_id = ? AND status <> 'DONE'",
                Integer.class, sessionId);
        Assertions.assertEquals(0, remainingTurns);
        Assertions.assertEquals(0, remainingEvents);
        Assertions.assertTrue(purgeTasks > 0, "删除会话时应在同一事务写入检查点清理任务");
    }

    @Test
    @Order(12)
    @DisplayName("提交日记与分析任务在同一事务写入，不等待Agent响应")
    void diarySubmissionCreatesDurableOutboxTask() throws Exception {
        EmotionDiaryRequest request = new EmotionDiaryRequest();
        request.setDiaryDate(java.time.LocalDate.now().toString());
        request.setMoodScore(6);
        request.setDominantEmotion("平静");
        request.setEmotionTriggers("工作结束");
        request.setDiaryContent("今天记录了一些日常感受");
        request.setSleepQuality(3);
        request.setStressLevel(2);

        mockMvc.perform(post("/emotion-diary")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        Long diaryId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM emotion_diary WHERE diary_content = ?",
                Long.class, request.getDiaryContent());
        Integer taskCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM emotion_analysis_outbox WHERE diary_id = ? AND status = 'PENDING'",
                Integer.class, diaryId);
        Assertions.assertEquals(1, taskCount);
        verify(realChatAgent, never()).analyzeDiary(anyMap());
    }

    @Test
    @Order(13)
    @DisplayName("Outbox以租约防重，并能重新派发过期任务")
    void diaryAnalysisOutboxRecoversExpiredLease() throws Exception {
        Long diaryId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM emotion_diary WHERE diary_content = ?",
                Long.class, "今天记录了一些日常感受");
        var queued = outboxService.claimNextForDispatch();
        Assertions.assertNotNull(queued);
        Assertions.assertEquals("QUEUED", queued.getStatus());
        var processing = outboxService.claimForProcessing(queued.getId(), queued.getLeaseToken());
        Assertions.assertNotNull(processing);
        Assertions.assertEquals(1, processing.getAttempts());

        jdbcTemplate.update("UPDATE emotion_analysis_outbox SET lease_until = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                processing.getId());
        var redelivered = outboxService.claimNextForDispatch();
        Assertions.assertNotNull(redelivered);
        Assertions.assertEquals("QUEUED", redelivered.getStatus());
        Assertions.assertNotEquals(processing.getLeaseToken(), redelivered.getLeaseToken());
        var recovered = outboxService.claimForProcessing(redelivered.getId(), redelivered.getLeaseToken());
        Assertions.assertNotNull(recovered);
        Assertions.assertEquals(2, recovered.getAttempts());
        Assertions.assertEquals(diaryId, recovered.getDiaryId());
    }

    @Test
    @Order(14)
    @DisplayName("个性化记忆必须由本人授权、管理，关闭授权后不再读取")
    void personalMemoryIsOwnerScopedAndOptIn() throws Exception {
        mockMvc.perform(get("/psychological-chat/memories/consent").header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(post("/psychological-chat/memories")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我喜欢简短建议\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"));

        mockMvc.perform(put("/psychological-chat/memories/consent")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        String response = mockMvc.perform(post("/psychological-chat/memories")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我喜欢简短建议\",\"expiresAt\":\"2027-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceType").value("USER_ENTERED"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> memoryData = (Map<String, Object>) objectMapper.readValue(response, Result.class).getData();
        Long memoryId = ((Number) memoryData.get("id")).longValue();

        mockMvc.perform(put("/psychological-chat/memories/consent")
                        .header("token", otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        mockMvc.perform(delete("/psychological-chat/memories/" + memoryId)
                        .header("token", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("404"));

        Long memoryPurgeSessionId = createSession(userToken);
        mockMvc.perform(put("/psychological-chat/memories/consent")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowed\":false}"))
                .andExpect(status().isOk());
        Assertions.assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_checkpoint_purge_outbox WHERE session_id = ? AND status <> 'DONE'",
                Integer.class, memoryPurgeSessionId) > 0,
                "撤回记忆授权时应持久化对应会话的检查点清理任务");
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_memory WHERE id = ? AND user_id = ?",
                Integer.class, memoryId, jdbcTemplate.queryForObject(
                        "SELECT id FROM user WHERE username = 'chatuser'", Long.class)));
    }

    @Test
    @Order(15)
    @DisplayName("过期流恢复后旧生成器不能写入新一代事件")
    void staleStreamGenerationIsFencedAfterRecovery() throws Exception {
        Long testSessionId = createSession(userToken);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
        String requestId = "stale-generation-" + System.nanoTime();
        String userMessage = "recovered-user-message";
        var oldGeneration = chatTurnStore.beginTurn(
                testSessionId, ownerId, requestId, sha256(userMessage), userMessage);
        chatTurnStore.appendChunk(testSessionId, requestId, oldGeneration.generationToken(), 0,
                "{\"code\":\"200\",\"data\":{\"content\":\"old-fragment\"}}");
        jdbcTemplate.update("UPDATE consultation_chat_turn SET updated_at = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) "
                + "WHERE session_id = ? AND client_request_id = ?", testSessionId, requestId);

        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap()))
                .thenReturn(Flux.just("recovered-new-response"));
        ChatStreamRequest request = new ChatStreamRequest();
        request.setSessionId(testSessionId.toString());
        request.setClientRequestId(requestId);
        request.setUserMessage(userMessage);
        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .header("Last-Event-ID", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andReturn();
        awaitTurnCompletion(testSessionId, requestId);
        ArgumentCaptor<Map> agentContextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(realChatAgent, times(1)).streamResponse(
                anyLong(), anyString(), anyList(), agentContextCaptor.capture());
        String currentGenerationToken = jdbcTemplate.queryForObject(
                "SELECT generation_token FROM consultation_chat_turn WHERE session_id = ? "
                        + "AND client_request_id = ?",
                String.class, testSessionId, requestId);
        Assertions.assertNotEquals(oldGeneration.generationToken(), currentGenerationToken);
        Assertions.assertEquals(currentGenerationToken,
                agentContextCaptor.getValue().get("generationToken"));
        MvcResult recovered = mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .header("Last-Event-ID", "0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andReturn();
        String replay = recovered.getResponse().getContentAsString();
        Assertions.assertTrue(replay.contains("event:reset"));
        Assertions.assertTrue(replay.contains("recovered-new-response"));

        chatTurnStore.appendChunk(testSessionId, requestId, oldGeneration.generationToken(), 20,
                "{\"code\":\"200\",\"data\":{\"content\":\"late-old-fragment\"}}");
        chatTurnStore.completeTurn(testSessionId, requestId, oldGeneration.generationToken(),
                "late-old-response", 21, "{\"code\":\"200\",\"data\":{\"done\":true}}");

        Integer aiMessageCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 2",
                Integer.class, testSessionId);
        Integer staleLateEventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_stream_event WHERE session_id = ? "
                        + "AND client_request_id = ? AND sequence >= 20",
                Integer.class, testSessionId, requestId);
        Assertions.assertEquals(1, aiMessageCount);
        Assertions.assertEquals(0, staleLateEventCount);
    }

    @Test
    @Order(16)
    @DisplayName("知识索引任务执行期间有新版本时会再次同步最新版本")
    void knowledgeIndexOutboxCoalescesChangesDuringProcessing() {
        Long articleId = 1L;
        knowledgeIndexOutboxService.enqueueArticle(articleId);
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_index_outbox WHERE article_id = ?", Long.class, articleId);

        var queued = knowledgeIndexOutboxService.claimNextForDispatch();
        Assertions.assertEquals(taskId, queued.getId());
        var processing = knowledgeIndexOutboxService.claimForProcessing(queued.getId(), queued.getLeaseToken());
        Assertions.assertNotNull(processing);
        knowledgeIndexOutboxService.enqueueArticle(articleId);

        Assertions.assertTrue(knowledgeIndexOutboxService.complete(processing));
        Assertions.assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_index_outbox WHERE id = ?", String.class, taskId));

        var secondDispatch = knowledgeIndexOutboxService.claimNextForDispatch();
        Assertions.assertEquals(taskId, secondDispatch.getId());
        var latest = knowledgeIndexOutboxService.claimForProcessing(
                secondDispatch.getId(), secondDispatch.getLeaseToken());
        Assertions.assertNotNull(latest);
        Assertions.assertTrue(knowledgeIndexOutboxService.complete(latest));
        Assertions.assertEquals("DONE", jdbcTemplate.queryForObject(
                "SELECT status FROM knowledge_index_outbox WHERE id = ?", String.class, taskId));
        Assertions.assertEquals(3L, jdbcTemplate.queryForObject(
                "SELECT desired_version FROM knowledge_index_outbox WHERE id = ?", Long.class, taskId));
    }

    @Test
    @Order(17)
    @DisplayName("同一会话不允许两个不同轮次并发生成")
    void activeTurnBlocksConcurrentTurnForSameSession() throws Exception {
        Long testSessionId = createSession(userToken);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
        String firstMessage = "第一条仍在生成的消息";
        String firstRequestId = "concurrent-first-" + System.nanoTime();
        var first = chatTurnStore.beginTurn(testSessionId, ownerId, firstRequestId,
                sha256(firstMessage), firstMessage);

        var duplicate = chatTurnStore.beginTurn(testSessionId, ownerId, firstRequestId,
                sha256(firstMessage), firstMessage);
        Assertions.assertFalse(duplicate.shouldGenerate(), "相同幂等键应恢复已有轮次");
        Assertions.assertEquals(first.generationToken(), duplicate.generationToken());

        var conflict = Assertions.assertThrows(com.amha.common.BusinessException.class,
                () -> chatTurnStore.beginTurn(testSessionId, ownerId, "concurrent-second-" + System.nanoTime(),
                        sha256Unchecked("第二条消息"), "第二条消息"));
        Assertions.assertEquals("409", conflict.getCode());
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND status = 'RUNNING'",
                Integer.class, testSessionId));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1",
                Integer.class, testSessionId));
    }

    @Test
    @Order(18)
    @DisplayName("知识索引撤下任务与记忆检查点清理失败后持续重试")
    void privacyCriticalOutboxesKeepRetryingAfterSeveralFailures() throws Exception {
        jdbcTemplate.update("UPDATE knowledge_index_outbox SET status = 'DONE', "
                + "lease_token = NULL, lease_until = NULL");
        Long articleId = 910000001L;
        knowledgeIndexOutboxService.enqueueArticle(articleId);
        Long indexTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM knowledge_index_outbox WHERE article_id = ?", Long.class, articleId);
        for (int attempt = 1; attempt <= 7; attempt++) {
            jdbcTemplate.update("UPDATE knowledge_index_outbox SET next_attempt_at = "
                    + "DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", indexTaskId);
            var queued = knowledgeIndexOutboxService.claimNextForDispatch();
            Assertions.assertEquals(indexTaskId, queued.getId());
            var processing = knowledgeIndexOutboxService.claimForProcessing(
                    queued.getId(), queued.getLeaseToken());
            Assertions.assertNotNull(processing);
            Assertions.assertTrue(knowledgeIndexOutboxService.fail(processing, "AGENT_SYNC_FAILED"));
            Assertions.assertEquals("PENDING", jdbcTemplate.queryForObject(
                    "SELECT status FROM knowledge_index_outbox WHERE id = ?", String.class, indexTaskId));
        }

        jdbcTemplate.update("UPDATE agent_checkpoint_purge_outbox SET status = 'DONE', "
                + "lease_token = NULL, lease_until = NULL");
        Long testSessionId = createSession(userToken);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
        checkpointPurgeOutboxService.enqueueSession(testSessionId, ownerId);
        Long purgeTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM agent_checkpoint_purge_outbox WHERE session_id = ?", Long.class, testSessionId);

        var firstClaim = checkpointPurgeOutboxService.claimNextForProcessing();
        Assertions.assertEquals(purgeTaskId, firstClaim.getId());
        checkpointPurgeOutboxService.enqueueSession(testSessionId, ownerId);
        Assertions.assertTrue(checkpointPurgeOutboxService.complete(firstClaim));
        Assertions.assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_checkpoint_purge_outbox WHERE id = ?", String.class, purgeTaskId),
                "清理过程中发生新记忆变更时，完成旧版本后必须再次清理");

        for (int attempt = 1; attempt <= 7; attempt++) {
            jdbcTemplate.update("UPDATE agent_checkpoint_purge_outbox SET next_attempt_at = "
                    + "DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", purgeTaskId);
            var claimed = checkpointPurgeOutboxService.claimNextForProcessing();
            Assertions.assertNotNull(claimed);
            Assertions.assertEquals(purgeTaskId, claimed.getId());
            Assertions.assertTrue(checkpointPurgeOutboxService.fail(claimed, "AGENT_PURGE_FAILED"));
            Assertions.assertEquals("PENDING", jdbcTemplate.queryForObject(
                    "SELECT status FROM agent_checkpoint_purge_outbox WHERE id = ?", String.class, purgeTaskId));
        }
    }

    @Test
    @Order(19)
    @DisplayName("首轮会话创建原子保存用户消息并对相同请求幂等")
    void firstTurnSessionStartIsAtomicAndIdempotent() throws Exception {
        String clientRequestId = "start-" + System.nanoTime();
        String message = "创建会话成功后即使页面刷新也应保留首条消息";
        String body = idempotentStartBody(message, "首轮恢复测试", clientRequestId);

        String firstResponse = mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.messageCount").value(1))
                .andReturn().getResponse().getContentAsString();
        long firstSessionId = objectMapper.readTree(firstResponse).path("data").path("sessionId").asLong();

        String retryResponse = mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(firstSessionId))
                .andReturn().getResponse().getContentAsString();

        Assertions.assertEquals(firstSessionId,
                objectMapper.readTree(retryResponse).path("data").path("sessionId").asLong());
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_session WHERE user_id = "
                        + "(SELECT user_id FROM consultation_session WHERE id = ?) AND start_request_id = ?",
                Integer.class, firstSessionId, clientRequestId));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                Integer.class, firstSessionId, message));
        Assertions.assertEquals("PENDING", jdbcTemplate.queryForObject(
                "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                String.class, firstSessionId, clientRequestId));
    }

    @Test
    @Order(20)
    @DisplayName("同一首轮幂等键不能被不同消息复用")
    void firstTurnIdempotencyKeyRejectsDifferentMessage() throws Exception {
        String clientRequestId = "start-conflict-" + System.nanoTime();
        String original = idempotentStartBody("原始内容", "首轮冲突测试", clientRequestId);
        mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(original))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        String changed = idempotentStartBody("不同内容", "首轮冲突测试", clientRequestId);
        mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changed))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("409"));

        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_session WHERE start_request_id = ?",
                Integer.class, clientRequestId));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE content = ?", Integer.class, "原始内容"));
    }

    @Test
    @Order(21)
    @DisplayName("首轮恢复接口只允许原会话用户读取")
    void firstTurnRecoveryIsScopedToSessionOwner() throws Exception {
        String clientRequestId = "start-recover-" + System.nanoTime();
        String request = idempotentStartBody("需要在刷新后重新连接", "首轮恢复权限测试", clientRequestId);
        String startResponse = mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(startResponse).path("data").path("sessionId").asLong();

        mockMvc.perform(get("/psychological-chat/session/start/{clientRequestId}", clientRequestId)
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId));

        mockMvc.perform(get("/psychological-chat/session/start/{clientRequestId}", clientRequestId)
                        .header("token", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @Order(22)
    @DisplayName("流式请求接管已持久化首轮而不重复写入用户消息")
    void streamClaimsPendingFirstTurnWithoutDuplicatingMessage() throws Exception {
        String clientRequestId = "start-stream-" + System.nanoTime();
        String message = "已随会话一起持久化的首条消息";
        String startRequest = idempotentStartBody(message, "首轮接管测试", clientRequestId);
        String startResponse = mockMvc.perform(post("/psychological-chat/session/start")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startRequest))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long sessionId = objectMapper.readTree(startResponse).path("data").path("sessionId").asLong();

        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap()))
                .thenReturn(Flux.just("首轮恢复成功"));
        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", String.valueOf(sessionId),
                                "clientRequestId", clientRequestId,
                                "userMessage", message)))
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk());

        awaitTurnCompletion(sessionId, clientRequestId);
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                Integer.class, sessionId, message));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 2 AND content = ?",
                Integer.class, sessionId, "首轮恢复成功"));
        verify(realChatAgent, times(1)).streamResponse(anyLong(), anyString(), anyList(), anyMap());
    }

    @Test
    @Order(23)
    @DisplayName("并发重试首轮创建只产生一个会话和一条用户消息")
    void concurrentFirstTurnStartsShareOneSession() throws Exception {
        String clientRequestId = "start-concurrent-" + System.nanoTime();
        String message = "并发请求也只应持久化一次";
        String body = idempotentStartBody(message, "并发首轮测试", clientRequestId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var request = (java.util.concurrent.Callable<String>) () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发请求未启动");
                return mockMvc.perform(post("/psychological-chat/session/start")
                                .header("token", userToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn().getResponse().getContentAsString();
            };
            Future<String> firstFuture = executor.submit(request);
            Future<String> secondFuture = executor.submit(request);
            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS), "两个请求都应到达屏障");
            start.countDown();
            var first = objectMapper.readTree(firstFuture.get(10, TimeUnit.SECONDS));
            var second = objectMapper.readTree(secondFuture.get(10, TimeUnit.SECONDS));
            Assertions.assertEquals("200", first.path("code").asText());
            Assertions.assertEquals("200", second.path("code").asText());
            long sessionId = first.path("data").path("sessionId").asLong();
            Assertions.assertEquals(sessionId, second.path("data").path("sessionId").asLong());
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_session WHERE start_request_id = ?",
                    Integer.class, clientRequestId));
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1",
                    Integer.class, sessionId));
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    Integer.class, sessionId, clientRequestId));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private String idempotentStartBody(String message, String title, String clientRequestId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "initialMessage", message,
                "sessionTitle", title,
                "clientRequestId", clientRequestId));
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private String sha256Unchecked(String value) {
        try {
            return sha256(value);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void awaitTurnCompletion(Long sessionId, String requestId) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        String status = "RUNNING";
        while (System.nanoTime() < deadline && "RUNNING".equals(status)) {
            status = jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, sessionId, requestId);
            if ("RUNNING".equals(status)) Thread.sleep(10);
        }
        Assertions.assertEquals("COMPLETED", status, "流式轮次应在等待窗口内进入完成状态");
    }

    private void awaitNoRunningTurns(Long sessionId) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Integer running = 1;
        while (System.nanoTime() < deadline && running != 0) {
            running = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND status = 'RUNNING'",
                    Integer.class, sessionId);
            if (running != 0) Thread.sleep(10);
        }
        Assertions.assertEquals(0, running, "会话中的流式轮次应进入终态");
    }
}
