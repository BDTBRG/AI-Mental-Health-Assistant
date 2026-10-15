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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.mockito.ArgumentCaptor;
import com.amha.service.impl.EmotionAnalysisOutboxService;
import com.amha.service.impl.ChatTurnStore;
import com.amha.service.impl.KnowledgeIndexOutboxService;
import com.amha.service.impl.ContextSummaryOutboxService;
import com.amha.entity.ConsultationContextSummary;
import com.amha.entity.ConsultationContextSummaryOutbox;
import com.amha.entity.ConsultationMessage;
import com.amha.mapper.ConsultationContextSummaryMapper;
import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.amha.mapper.ConsultationSessionMapper;
import com.amha.mapper.UserMapper;
import com.amha.entity.User;
import com.amha.config.ChatContextSummaryProperties;
import com.amha.service.impl.ChatContextAssembler;
import com.amha.service.ChatService;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
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
    private ConsultationContextSummaryMapper contextSummaryMapper;

    @Autowired
    private ConsultationContextSummaryOutboxMapper contextSummaryOutboxMapper;

    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ChatContextSummaryProperties chatContextSummaryProperties;

    @SpyBean
    private ChatContextAssembler chatContextAssembler;

    @Autowired
    private ChatService chatService;

    @Autowired
    private EmotionAnalysisOutboxService outboxService;

    @Autowired
    private ChatTurnStore chatTurnStore;

    @Autowired
    private KnowledgeIndexOutboxService knowledgeIndexOutboxService;

    @SpyBean
    private ContextSummaryOutboxService contextSummaryOutboxService;

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
    @Order(100)
    @DisplayName("摘要任务重放仅管理员可执行且不返回摘要正文")
    void onlyAdminCanRetryDeadContextSummaryWithoutReturningSummaryContent() throws Exception {
        Long retrySessionId = createSession(userToken);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, retrySessionId);
        Long adminId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = 'admin'", Long.class);
        ConsultationContextSummaryOutbox deadTask = new ConsultationContextSummaryOutbox();
        deadTask.setSessionId(retrySessionId);
        deadTask.setUserId(ownerId);
        deadTask.setDesiredThroughMessageId(1L);
        deadTask.setStatus("DEAD");
        deadTask.setAttemptCount(8);
        deadTask.setNextAttemptAt(java.time.LocalDateTime.now().minusMinutes(5));
        deadTask.setLastErrorCode("UPSTREAM_TIMEOUT");
        deadTask.setLeaseToken("expired-lease-token");
        deadTask.setLeaseUntil(java.time.LocalDateTime.now().minusMinutes(1));
        deadTask.setManualRetryCount(2);
        contextSummaryOutboxMapper.insert(deadTask);

        String adminResponse = mockMvc.perform(post("/api/admin/context-summary/" + retrySessionId + "/retry")
                        .header("token", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.retried").value(true))
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertFalse(adminResponse.contains("summary_json"));
        org.junit.jupiter.api.Assertions.assertFalse(adminResponse.contains("sourceMessageIds"));
        verify(contextSummaryOutboxService).retryDead(eq(retrySessionId), eq(adminId));

        ConsultationContextSummaryOutbox retried = contextSummaryOutboxMapper
                .selectBySessionAndUser(retrySessionId, ownerId);
        Assertions.assertEquals("PENDING", retried.getStatus());
        Assertions.assertEquals(0, retried.getAttemptCount());
        Assertions.assertEquals(3, retried.getManualRetryCount());
        Assertions.assertEquals(adminId, retried.getLastManualRetryBy());
        Assertions.assertNotNull(retried.getLastManualRetryAt());
        Assertions.assertNotNull(retried.getNextAttemptAt());
        Assertions.assertNull(retried.getLastErrorCode());
        Assertions.assertNull(retried.getLeaseToken());
        Assertions.assertNull(retried.getLeaseUntil());

        mockMvc.perform(post("/api/admin/context-summary/" + retrySessionId + "/retry")
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("403"));
        verify(contextSummaryOutboxService, times(1)).retryDead(eq(retrySessionId), eq(adminId));
        ConsultationContextSummaryOutbox afterDeniedRetry = contextSummaryOutboxMapper
                .selectBySessionAndUser(retrySessionId, ownerId);
        Assertions.assertEquals(3, afterDeniedRetry.getManualRetryCount());
        Assertions.assertEquals(adminId, afterDeniedRetry.getLastManualRetryBy());
    }

    @Test
    @Order(101)
    @DisplayName("摘要迁移表支持水位读取、CAS 更新和待处理任务领取查询")
    void contextSummaryMappersExecuteAgainstIsolatedH2Schema() {
        long testSessionId = Math.abs(System.nanoTime());
        long testUserId = testSessionId + 1;
        try {
            ConsultationMessage first = new ConsultationMessage();
            first.setSessionId(testSessionId);
            first.setSenderType(1);
            first.setContent("earlier");
            consultationMessageMapper.insert(first);

            ConsultationMessage second = new ConsultationMessage();
            second.setSessionId(testSessionId);
            second.setSenderType(2);
            second.setContent("summarize this");
            consultationMessageMapper.insert(second);

            ConsultationContextSummary summary = new ConsultationContextSummary();
            summary.setSessionId(testSessionId);
            summary.setUserId(testUserId);
            summary.setSummaryJson("{}");
            summary.setCoveredThroughMessageId(first.getId());
            summary.setSummaryVersion(1L);
            summary.setPromptVersion("context-summary-v1");
            contextSummaryMapper.insert(summary);

            ConsultationContextSummaryOutbox task = new ConsultationContextSummaryOutbox();
            task.setSessionId(testSessionId);
            task.setUserId(testUserId);
            task.setDesiredThroughMessageId(second.getId());
            task.setStatus("PENDING");
            task.setAttemptCount(0);
            task.setNextAttemptAt(java.time.LocalDateTime.now().minusDays(1));
            contextSummaryOutboxMapper.insert(task);
            // This mapper selects globally, so retire earlier synthetic tasks from this test class.
            jdbcTemplate.update("UPDATE consultation_context_summary_outbox SET status = 'DONE' WHERE session_id <> ?",
                    testSessionId);

            Assertions.assertEquals(task.getId(), contextSummaryOutboxMapper.selectNextReadyForUpdate().getId());
            Assertions.assertEquals(1, contextSummaryMapper.updateSummaryCas(testSessionId, testUserId, 1L,
                    "{\"facts\":[]}", second.getId(), "context-summary-v1", java.time.LocalDateTime.now()));
            Assertions.assertEquals(second.getId(), contextSummaryMapper.selectById(testSessionId)
                    .getCoveredThroughMessageId());
            Assertions.assertEquals(second.getId(), consultationMessageMapper.selectWindowAfterWatermark(
                    testSessionId, first.getId(), second.getId(), 40).get(0).getId());
        } finally {
            jdbcTemplate.update("DELETE FROM consultation_context_summary_outbox WHERE session_id = ?", testSessionId);
            jdbcTemplate.update("DELETE FROM consultation_context_summary WHERE session_id = ?", testSessionId);
            jdbcTemplate.update("DELETE FROM consultation_message WHERE session_id = ?", testSessionId);
        }
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

        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
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
        verify(realChatAgent, times(1)).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
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
        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
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

        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, sessionId);
        Long summaryTarget = consultationMessageMapper.selectMaxMessageId(sessionId);
        Assertions.assertNotNull(summaryTarget);
        String waitingRequestId = "delete-context-" + System.nanoTime();
        chatTurnStore.waitForContext(sessionId, ownerId, waitingRequestId,
                "delete-context-request-hash", "等待摘要后删除", summaryTarget);
        ConsultationContextSummary summary = new ConsultationContextSummary();
        summary.setSessionId(sessionId);
        summary.setUserId(ownerId);
        summary.setSummaryJson("{\"userStatedContext\":[],\"userPreferences\":[],\"openTopics\":[]}");
        summary.setCoveredThroughMessageId(summaryTarget);
        summary.setSummaryVersion(1L);
        summary.setPromptVersion("context-summary-v1");
        contextSummaryMapper.insert(summary);

        mockMvc.perform(delete("/psychological-chat/sessions/" + sessionId)
                        .header("token", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
        verify(contextSummaryOutboxService).deleteForSession(sessionId);
        Integer remainingTurns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ?",
                Integer.class, sessionId);
        Integer remainingEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_stream_event WHERE session_id = ?",
                Integer.class, sessionId);
        Integer purgeTasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_checkpoint_purge_outbox WHERE session_id = ? AND status <> 'DONE'",
                Integer.class, sessionId);
        Integer remainingSummaries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_context_summary WHERE session_id = ?",
                Integer.class, sessionId);
        Integer remainingSummaryTasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_context_summary_outbox WHERE session_id = ?",
                Integer.class, sessionId);
        Assertions.assertEquals(0, remainingTurns);
        Assertions.assertEquals(0, remainingEvents);
        Assertions.assertEquals(0, remainingSummaries);
        Assertions.assertEquals(0, remainingSummaryTasks);
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
        Long oldGenerationEpoch = jdbcTemplate.queryForObject(
                "SELECT generation_epoch FROM consultation_chat_turn WHERE session_id = ? "
                        + "AND client_request_id = ?",
                Long.class, testSessionId, requestId);
        Long turnSequence = jdbcTemplate.queryForObject(
                "SELECT id FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                Long.class, testSessionId, requestId);
        jdbcTemplate.update("UPDATE consultation_chat_turn SET updated_at = DATEADD('MINUTE', -10, CURRENT_TIMESTAMP) "
                + "WHERE session_id = ? AND client_request_id = ?", testSessionId, requestId);

        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
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
                anyLong(), anyString(), anyList(), anyMap(), agentContextCaptor.capture());
        String currentGenerationToken = jdbcTemplate.queryForObject(
                "SELECT generation_token FROM consultation_chat_turn WHERE session_id = ? "
                        + "AND client_request_id = ?",
                String.class, testSessionId, requestId);
        Long currentGenerationEpoch = jdbcTemplate.queryForObject(
                "SELECT generation_epoch FROM consultation_chat_turn WHERE session_id = ? "
                        + "AND client_request_id = ?",
                Long.class, testSessionId, requestId);
        Assertions.assertNotEquals(oldGeneration.generationToken(), currentGenerationToken);
        Assertions.assertEquals(oldGenerationEpoch + 1L, currentGenerationEpoch);
        Assertions.assertEquals(currentGenerationToken,
                agentContextCaptor.getValue().get("generationToken"));
        Assertions.assertEquals(currentGenerationEpoch,
                agentContextCaptor.getValue().get("generationEpoch"));
        Assertions.assertEquals(turnSequence,
                agentContextCaptor.getValue().get("turnSequence"));
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

        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
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
        verify(realChatAgent, times(1)).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
    }

    @Test
    @Order(24)
    @DisplayName("摘要待处理在 SSE 建立前返回 JSON 重试提示并保存同一轮")
    void summaryPendingReturnsJsonEnvelopeBeforeSseAndPersistsTurn() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOverflowingHistory(testSessionId);
        String requestId = "context-pending-" + System.nanoTime();
        String message = "继续刚才的话题";
        chatContextSummaryProperties.setPreflightEnabled(true);
        chatContextSummaryProperties.setWorkerEnabled(true);
        chatContextSummaryProperties.setAgentEnabled(true);
        ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
        try {
            MvcResult result = mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", requestId,
                                    "userMessage", message))))
                    .andExpect(status().isOk())
                    .andReturn();

            Assertions.assertFalse(result.getRequest().isAsyncStarted(),
                    "上下文预检应在创建 SSE emitter 前同步返回");
            Assertions.assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                    MediaType.parseMediaType(result.getResponse().getContentType())));
            Assertions.assertEquals("CONTEXT_SUMMARY_PENDING",
                    objectMapper.readTree(result.getResponse().getContentAsString()).path("code").asText());
            Assertions.assertEquals(requestId,
                    objectMapper.readTree(result.getResponse().getContentAsString())
                            .path("data").path("clientRequestId").asText());
            Assertions.assertTrue(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("retryable").asBoolean());
            Assertions.assertTrue(objectMapper.readTree(result.getResponse().getContentAsString())
                    .path("data").path("retryAfterMs").asLong() > 0);
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                    Integer.class, testSessionId, message));
            Assertions.assertEquals("WAITING_CONTEXT", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, testSessionId, requestId));
            verify(contextSummaryOutboxService).enqueue(eq(testSessionId), anyLong(), anyLong());
            verify(realChatAgent, never()).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
        } finally {
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);
        }
    }

    @Test
    @Order(25)
    @DisplayName("摘要功能不可用时保留请求 ID 并返回可恢复错误")
    void unavailableSummaryReturnsRetryableEnvelopeAndDoesNotCallAgent() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOverflowingHistory(testSessionId);
        String requestId = "context-unavailable-" + System.nanoTime();
        String message = "同一请求稍后继续";

        MvcResult result = mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", String.valueOf(testSessionId),
                                "clientRequestId", requestId,
                                "userMessage", message))))
                .andExpect(status().isOk())
                .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        Assertions.assertEquals("CONTEXT_SUMMARY_UNAVAILABLE", json.path("code").asText());
        Assertions.assertEquals(requestId, json.path("data").path("clientRequestId").asText());
        Assertions.assertTrue(json.path("data").path("retryable").asBoolean());
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                Integer.class, testSessionId, message));
        Assertions.assertEquals("CONTEXT_UNAVAILABLE", jdbcTemplate.queryForObject(
                "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                String.class, testSessionId, requestId));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                Integer.class, testSessionId, requestId));

        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", String.valueOf(testSessionId),
                                "clientRequestId", "second-unavailable-" + System.nanoTime(),
                                "userMessage", "另一条消息也不应被永久阻塞"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));
        verify(realChatAgent, never()).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
    }

    @Test
    @Order(29)
    @DisplayName("摘要任务不可用时释放已有等待轮次，摘要恢复后可用原请求 ID 继续")
    void unavailableSummaryReleasesWaitingTurnAndAllowsRecovery() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOverflowingHistory(testSessionId);
        String requestId = "context-recovery-" + System.nanoTime();
        String message = "我希望在摘要服务恢复后继续";
        chatContextSummaryProperties.setPreflightEnabled(true);
        chatContextSummaryProperties.setWorkerEnabled(true);
        chatContextSummaryProperties.setAgentEnabled(true);
        ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
        try {
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", requestId,
                                    "userMessage", message))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_PENDING"));

            jdbcTemplate.update("UPDATE consultation_context_summary_outbox SET status = 'DEAD' WHERE session_id = ?",
                    testSessionId);
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);

            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", requestId,
                                    "userMessage", message))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));
            Assertions.assertEquals("CONTEXT_UNAVAILABLE", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, testSessionId, requestId));

            Long ownerId = jdbcTemplate.queryForObject(
                    "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
            Long firstMessageId = jdbcTemplate.queryForObject(
                    "SELECT MIN(id) FROM consultation_message WHERE session_id = ?", Long.class, testSessionId);
            ConsultationContextSummary summary = new ConsultationContextSummary();
            summary.setSessionId(testSessionId);
            summary.setUserId(ownerId);
            summary.setSummaryJson("{\"userStatedContext\":[{\"text\":\"较早上下文已恢复\","
                    + "\"sourceMessageIds\":[" + firstMessageId
                    + "]}],\"userPreferences\":[],\"openTopics\":[]}");
            summary.setCoveredThroughMessageId(firstMessageId);
            summary.setSummaryVersion(1L);
            summary.setPromptVersion("context-summary-v1");
            contextSummaryMapper.insert(summary);
            chatContextSummaryProperties.setPreflightEnabled(true);
            chatContextSummaryProperties.setWorkerEnabled(true);
            chatContextSummaryProperties.setAgentEnabled(true);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
            when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
                    .thenReturn(Flux.just("恢复后的回答"));

            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", requestId,
                                    "userMessage", message))))
                    .andExpect(status().isOk());
            awaitTurnCompletion(testSessionId, requestId);
            Assertions.assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, testSessionId, requestId));
            verify(realChatAgent).streamResponse(eq(testSessionId), eq(message), anyList(), anyMap(), anyMap());
        } finally {
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);
        }
    }

    @Test
    @Order(32)
    @DisplayName("新轮次启动后旧的上下文不可用请求转为不可重试，避免恢复越序")
    void laterTurnSupersedesUnavailableRequestAndOldRequestCannotResume() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOverflowingHistory(testSessionId);
        String oldRequestId = "context-superseded-" + System.nanoTime();
        String oldMessage = "先前请求在摘要服务恢复后继续";
        String nextRequestId = "context-after-unavailable-" + System.nanoTime();
        String nextMessage = "先继续新的对话轮次";
        chatContextSummaryProperties.setPreflightEnabled(true);
        chatContextSummaryProperties.setWorkerEnabled(true);
        chatContextSummaryProperties.setAgentEnabled(true);
        ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
        try {
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", oldRequestId,
                                    "userMessage", oldMessage))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_PENDING"));

            jdbcTemplate.update("UPDATE consultation_context_summary_outbox SET status = 'DEAD' WHERE session_id = ?",
                    testSessionId);
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);

            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", oldRequestId,
                                    "userMessage", oldMessage))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));

            Long ownerId = jdbcTemplate.queryForObject(
                    "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
            Long firstMessageId = jdbcTemplate.queryForObject(
                    "SELECT MIN(id) FROM consultation_message WHERE session_id = ?", Long.class, testSessionId);
            ConsultationContextSummary summary = new ConsultationContextSummary();
            summary.setSessionId(testSessionId);
            summary.setUserId(ownerId);
            summary.setSummaryJson("{\"userStatedContext\":[{\"text\":\"较早上下文已恢复\","
                    + "\"sourceMessageIds\":[" + firstMessageId
                    + "]}],\"userPreferences\":[],\"openTopics\":[]}");
            summary.setCoveredThroughMessageId(firstMessageId);
            summary.setSummaryVersion(1L);
            summary.setPromptVersion("context-summary-v1");
            contextSummaryMapper.insert(summary);
            chatContextSummaryProperties.setPreflightEnabled(true);
            chatContextSummaryProperties.setWorkerEnabled(true);
            chatContextSummaryProperties.setAgentEnabled(true);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
            when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
                    .thenReturn(Flux.just("继续对话的回答"));

            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", nextRequestId,
                                    "userMessage", nextMessage))))
                    .andExpect(status().isOk());
            awaitTurnCompletion(testSessionId, nextRequestId);
            Assertions.assertEquals("CONTEXT_SUPERSEDED", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, testSessionId, oldRequestId));

            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", oldRequestId,
                                    "userMessage", oldMessage))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_REQUEST_SUPERSEDED"))
                    .andExpect(jsonPath("$.data.retryable").value(false))
                    .andExpect(jsonPath("$.data.clientRequestId").value(oldRequestId));
            verify(realChatAgent).streamResponse(eq(testSessionId), eq(nextMessage), anyList(), anyMap(), anyMap());
        } finally {
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);
        }
    }

    @Test
    @Order(33)
    @DisplayName("后续待整理、不可用或超限请求持久化后也终结旧的不可用请求")
    void persistedWaitingUnavailableAndRejectedTurnsSupersedeOlderUnavailableTurns() throws Exception {
        try {
            Long waitingSessionId = createSession(userToken);
            seedOverflowingHistory(waitingSessionId);
            String waitingOldId = "old-before-waiting-" + System.nanoTime();
            String waitingOldMessage = "等待请求前的不可用消息";
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(waitingSessionId),
                                    "clientRequestId", waitingOldId,
                                    "userMessage", waitingOldMessage))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));

            chatContextSummaryProperties.setPreflightEnabled(true);
            chatContextSummaryProperties.setWorkerEnabled(true);
            chatContextSummaryProperties.setAgentEnabled(true);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
            String waitingNextId = "next-waiting-" + System.nanoTime();
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(waitingSessionId),
                                    "clientRequestId", waitingNextId,
                                    "userMessage", "后续请求进入待整理"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_PENDING"));
            Assertions.assertEquals("CONTEXT_SUPERSEDED", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, waitingSessionId, waitingOldId));
            Assertions.assertEquals("WAITING_CONTEXT", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, waitingSessionId, waitingNextId));

            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);

            Long unavailableSessionId = createSession(userToken);
            seedOverflowingHistory(unavailableSessionId);
            String unavailableOldId = "old-before-unavailable-" + System.nanoTime();
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(unavailableSessionId),
                                    "clientRequestId", unavailableOldId,
                                    "userMessage", "不可用请求前的消息"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));
            String unavailableNextId = "next-unavailable-" + System.nanoTime();
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(unavailableSessionId),
                                    "clientRequestId", unavailableNextId,
                                    "userMessage", "后续摘要服务仍不可用"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));
            Assertions.assertEquals("CONTEXT_SUPERSEDED", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, unavailableSessionId, unavailableOldId));
            Assertions.assertEquals("CONTEXT_UNAVAILABLE", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, unavailableSessionId, unavailableNextId));

            Long rejectedSessionId = createSession(userToken);
            seedOverflowingHistory(rejectedSessionId);
            String rejectedOldId = "old-before-rejected-" + System.nanoTime();
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(rejectedSessionId),
                                    "clientRequestId", rejectedOldId,
                                    "userMessage", "超限请求前的不可用消息"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_SUMMARY_UNAVAILABLE"));
            seedOversizedRecentHistory(rejectedSessionId);
            String rejectedNextId = "next-rejected-" + System.nanoTime();
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(rejectedSessionId),
                                    "clientRequestId", rejectedNextId,
                                    "userMessage", "后续请求超过最近上下文窗口"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("CONTEXT_WINDOW_TOO_LARGE"));
            Assertions.assertEquals("CONTEXT_SUPERSEDED", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, rejectedSessionId, rejectedOldId));
            Assertions.assertEquals("CONTEXT_REJECTED", jdbcTemplate.queryForObject(
                    "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    String.class, rejectedSessionId, rejectedNextId));
        } finally {
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);
        }
    }

    @Test
    @Order(30)
    @DisplayName("单条超长消息在接口层拒绝，不写入会话或轮次")
    void rejectsOversizedSingleMessageBeforePersistence() throws Exception {
        Long testSessionId = createSession(userToken);
        String requestId = "too-large-message-" + System.nanoTime();
        String oversizedMessage = "x".repeat(8_001);

        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", String.valueOf(testSessionId),
                                "clientRequestId", requestId,
                                "userMessage", oversizedMessage))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                Integer.class, testSessionId, oversizedMessage));
        Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                Integer.class, testSessionId, requestId));
        verify(realChatAgent, never()).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
    }

    @Test
    @Order(31)
    @DisplayName("允许范围内的长消息保留正文并将会话预览限制在数据库列宽内")
    void acceptedLongMessagesKeepFullBodyAndBoundSessionPreview() throws Exception {
        Long testSessionId = createSession(userToken);
        String requestId = "long-message-" + System.nanoTime();
        String longMessage = "用户补充的背景".repeat(250);
        String longAnswer = "AI回复内容".repeat(250);
        when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
                .thenReturn(Flux.just(longAnswer));

        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", String.valueOf(testSessionId),
                                "clientRequestId", requestId,
                                "userMessage", longMessage))))
                .andExpect(status().isOk());
        awaitTurnCompletion(testSessionId, requestId);

        Assertions.assertEquals(longMessage, jdbcTemplate.queryForObject(
                "SELECT content FROM consultation_message WHERE session_id = ? AND sender_type = 1 "
                        + "AND content = ?", String.class, testSessionId, longMessage));
        Assertions.assertEquals(longAnswer, jdbcTemplate.queryForObject(
                "SELECT content FROM consultation_message WHERE session_id = ? AND sender_type = 2 "
                        + "AND content = ?", String.class, testSessionId, longAnswer));
        Assertions.assertEquals(1000, jdbcTemplate.queryForObject(
                "SELECT CHAR_LENGTH(last_message_content) FROM consultation_session WHERE id = ?",
                Integer.class, testSessionId));
    }

    @Test
    @Order(26)
    @DisplayName("最近原文窗口超限时保存终态与服务端请求 ID，重试不重复落库")
    void oversizedRecentWindowUsesTerminalContextErrorAndGeneratedRequestId() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOversizedRecentHistory(testSessionId);
        String message = "继续这个话题";
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "sessionId", String.valueOf(testSessionId),
                "userMessage", message));

        MvcResult first = mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andReturn();
        Assertions.assertFalse(first.getRequest().isAsyncStarted());
        var firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        Assertions.assertEquals("CONTEXT_WINDOW_TOO_LARGE", firstJson.path("code").asText());
        Assertions.assertFalse(firstJson.path("data").path("retryable").asBoolean());
        String generatedRequestId = firstJson.path("data").path("clientRequestId").asText();
        Assertions.assertFalse(generatedRequestId.isBlank());

        mockMvc.perform(post("/psychological-chat/stream")
                        .header("token", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sessionId", String.valueOf(testSessionId),
                                "clientRequestId", generatedRequestId,
                                "userMessage", message))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("CONTEXT_WINDOW_TOO_LARGE"))
                .andExpect(jsonPath("$.data.clientRequestId").value(generatedRequestId));

        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                Integer.class, testSessionId, message));
        Assertions.assertEquals("CONTEXT_REJECTED", jdbcTemplate.queryForObject(
                "SELECT status FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                String.class, testSessionId, generatedRequestId));
        Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                Integer.class, testSessionId, generatedRequestId));
        verify(realChatAgent, never()).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
    }

    @Test
    @Order(27)
    @DisplayName("待摘要首轮在请求 ID 恢复后只注入摘要一次且不泄露给会话消息 API")
    void waitingContextRetryRestoresGeneratedRequestIdAndInjectsSummarySeparately() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOverflowingHistory(testSessionId);
        String message = "请接着之前的内容继续";
        String summaryText = "用户较早提到工作压力明显";
        chatContextSummaryProperties.setPreflightEnabled(true);
        chatContextSummaryProperties.setWorkerEnabled(true);
        chatContextSummaryProperties.setAgentEnabled(true);
        ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
        try {
            MvcResult pending = mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "userMessage", message))))
                    .andExpect(status().isOk())
                    .andReturn();
            var pendingJson = objectMapper.readTree(pending.getResponse().getContentAsString());
            Assertions.assertEquals("CONTEXT_SUMMARY_PENDING", pendingJson.path("code").asText());
            String generatedRequestId = pendingJson.path("data").path("clientRequestId").asText();
            Assertions.assertFalse(generatedRequestId.isBlank());

            Long ownerId = jdbcTemplate.queryForObject(
                    "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
            Long summaryWatermark = jdbcTemplate.queryForObject(
                    "SELECT MIN(id) FROM consultation_message WHERE session_id = ?",
                    Long.class, testSessionId);
            ConsultationContextSummary summary = new ConsultationContextSummary();
            summary.setSessionId(testSessionId);
            summary.setUserId(ownerId);
            summary.setSummaryJson("{\"userStatedContext\":[{\"text\":\"" + summaryText
                    + "\",\"sourceMessageIds\":[" + summaryWatermark
                    + "]}],\"userPreferences\":[],\"openTopics\":[]}");
            summary.setCoveredThroughMessageId(summaryWatermark);
            summary.setSummaryVersion(1L);
            summary.setPromptVersion("context-summary-v1");
            contextSummaryMapper.insert(summary);

            when(realChatAgent.streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap()))
                    .thenReturn(Flux.just("我会结合前面的背景继续回应"));
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", userToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", generatedRequestId,
                                    "userMessage", message))))
                    .andExpect(status().isOk());
            awaitTurnCompletion(testSessionId, generatedRequestId);

            ArgumentCaptor<List<ConsultationMessage>> historyCaptor = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
            verify(realChatAgent).streamResponse(eq(testSessionId), eq(message), historyCaptor.capture(),
                    summaryCaptor.capture(), anyMap());
            Assertions.assertEquals(11, historyCaptor.getValue().size());
            Assertions.assertTrue(historyCaptor.getValue().stream()
                    .noneMatch(item -> message.equals(item.getContent())));
            Map<?, ?> summaryEntry = (Map<?, ?>) ((List<?>) summaryCaptor.getValue()
                    .get("userStatedContext")).get(0);
            Assertions.assertEquals(summaryText, summaryEntry.get("text"));
            Assertions.assertEquals(summaryWatermark,
                    ((Number) ((List<?>) summaryEntry.get("sourceMessageIds")).get(0)).longValue());
            Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                    Integer.class, testSessionId, message));

            MvcResult messagesResponse = mockMvc.perform(get(
                            "/psychological-chat/sessions/" + testSessionId + "/messages")
                            .header("token", userToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String messagePayload = messagesResponse.getResponse().getContentAsString();
            Assertions.assertFalse(messagePayload.contains(summaryText));
            Assertions.assertFalse(messagePayload.contains("sourceMessageIds"));
        } finally {
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);
        }
    }

    @Test
    @Order(28)
    @DisplayName("非所有者不能通过长会话预检创建待摘要轮次或 Outbox")
    void otherUserCannotCreateSummaryWorkForLongSession() throws Exception {
        Long testSessionId = createSession(userToken);
        seedOverflowingHistory(testSessionId);
        String requestId = "foreign-context-" + System.nanoTime();
        chatContextSummaryProperties.setPreflightEnabled(true);
        chatContextSummaryProperties.setWorkerEnabled(true);
        chatContextSummaryProperties.setAgentEnabled(true);
        ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", true);
        try {
            mockMvc.perform(post("/psychological-chat/stream")
                            .header("token", otherUserToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "sessionId", String.valueOf(testSessionId),
                                    "clientRequestId", requestId,
                                    "userMessage", "不能跨用户提交摘要任务"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("403"));

            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_chat_turn WHERE session_id = ? AND client_request_id = ?",
                    Integer.class, testSessionId, requestId));
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? AND sender_type = 1 AND content = ?",
                    Integer.class, testSessionId, "不能跨用户提交摘要任务"));
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_context_summary_outbox WHERE session_id = ?",
                    Integer.class, testSessionId));
            verify(realChatAgent, never()).streamResponse(anyLong(), anyString(), anyList(), anyMap(), anyMap());
        } finally {
            chatContextSummaryProperties.setPreflightEnabled(false);
            chatContextSummaryProperties.setWorkerEnabled(false);
            chatContextSummaryProperties.setAgentEnabled(false);
            ReflectionTestUtils.setField(chatContextAssembler, "outboxEnabled", false);
        }
    }

    @Test
    @Order(34)
    @DisplayName("预检后的会话快照过期时拒绝使用旧上下文生成")
    void stalePreparedSnapshotCannotStartAfterAnotherTurnCompletes() throws Exception {
        Long testSessionId = createSession(userToken);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM consultation_session WHERE id = ?", Long.class, testSessionId);
        String staleMessage = "预检完成后才提交的旧快照请求";
        String interveningMessage = "先完成的新一轮消息";
        String interveningRequestId = "intervening-" + System.nanoTime();
        CountDownLatch snapshotPrepared = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);

        doAnswer(invocation -> {
            ChatContextPreparation prepared = (ChatContextPreparation) invocation.callRealMethod();
            if (staleMessage.equals(invocation.getArgument(2))) {
                snapshotPrepared.countDown();
                if (!releaseSnapshot.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release prepared chat context");
                }
            }
            return prepared;
        }).when(chatContextAssembler).prepare(anyLong(), anyLong(), anyString(), anyBoolean(), anyMap());

        ChatStreamRequest request = new ChatStreamRequest();
        request.setSessionId(testSessionId.toString());
        request.setClientRequestId("stale-snapshot-" + System.nanoTime());
        request.setUserMessage(staleMessage);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> pendingRequest = executor.submit(() -> chatService.streamChat(request, ownerId, 0L));
            Assertions.assertTrue(snapshotPrepared.await(10, TimeUnit.SECONDS),
                    "The stale request must pause after context preflight");

            ChatTurnStore.TurnStart intervening = chatTurnStore.beginTurn(testSessionId, ownerId,
                    interveningRequestId, sha256Unchecked(interveningMessage), interveningMessage);
            chatTurnStore.completeTurn(testSessionId, interveningRequestId, intervening.generationToken(),
                    "intervening reply", 0, "{\"code\":\"200\",\"data\":{\"done\":true}}");

            releaseSnapshot.countDown();
            java.util.concurrent.ExecutionException executionError = Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> pendingRequest.get(10, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(com.amha.common.ChatContextPreparationException.class,
                    executionError.getCause());
            Assertions.assertEquals("CONTEXT_SNAPSHOT_STALE",
                    ((com.amha.common.ChatContextPreparationException) executionError.getCause()).getErrorCode());
            Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM consultation_message WHERE session_id = ? "
                            + "AND sender_type = 1 AND content = ?",
                    Integer.class, testSessionId, staleMessage));
            verify(realChatAgent, never()).streamResponse(eq(testSessionId), eq(staleMessage),
                    anyList(), anyMap(), anyMap());
        } finally {
            releaseSnapshot.countDown();
            executor.shutdownNow();
        }
    }

    private void seedOverflowingHistory(Long targetSessionId) {
        for (int index = 0; index < 12; index++) {
            ConsultationMessage message = new ConsultationMessage();
            message.setSessionId(targetSessionId);
            message.setSenderType(index % 2 == 0 ? 1 : 2);
            message.setContent("history-" + index + "-" + "x".repeat(2_800));
            consultationMessageMapper.insert(message);
        }
    }

    private void seedOversizedRecentHistory(Long targetSessionId) {
        for (int index = 0; index < 12; index++) {
            ConsultationMessage message = new ConsultationMessage();
            message.setSessionId(targetSessionId);
            message.setSenderType(index % 2 == 0 ? 1 : 2);
            message.setContent("recent-" + index + "-" + "x".repeat(3_000));
            consultationMessageMapper.insert(message);
        }
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
