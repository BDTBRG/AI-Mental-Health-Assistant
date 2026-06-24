package com.amha;

import com.amha.common.Result;
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
class DataAnalyticsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String token;

    @BeforeEach
    void setUp() throws Exception {
        if (token == null) {
            RegisterRequest regReq = new RegisterRequest();
            regReq.setUsername("analyticsadmin");
            regReq.setPassword("test123");
            regReq.setConfirmPassword("test123");
            regReq.setEmail("analytics@test.com");
            regReq.setGender(1);
            regReq.setUserType(2);
            mockMvc.perform(post("/user/add")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(regReq)));

            LoginRequest loginReq = new LoginRequest();
            loginReq.setUsername("analyticsadmin");
            loginReq.setPassword("test123");
            String resp = mockMvc.perform(post("/user/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(loginReq)))
                    .andReturn().getResponse().getContentAsString();
            Result<?> result = objectMapper.readValue(resp, Result.class);
            var dataMap = (Map<String, Object>) result.getData();
            token = (String) dataMap.get("token");
        }
    }

    @Test
    @DisplayName("获取仪表盘综合数据")
    void testGetOverview() throws Exception {
        mockMvc.perform(get("/data-analytics/overview")
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.systemOverview").exists())
                .andExpect(jsonPath("$.data.systemOverview.totalUsers").isNumber())
                .andExpect(jsonPath("$.data.emotionTrend").isArray())
                .andExpect(jsonPath("$.data.consultationStats").exists())
                .andExpect(jsonPath("$.data.userActivity").isArray());
    }
}
