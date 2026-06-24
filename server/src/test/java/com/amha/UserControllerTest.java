package com.amha;

import com.amha.common.Result;
import com.amha.dto.LoginRequest;
import com.amha.dto.LoginResponse;
import com.amha.dto.RegisterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static String token;

    @Test
    @Order(1)
    @DisplayName("注册新用户")
    void testRegister() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("pass123");
        request.setConfirmPassword("pass123");
        request.setEmail("newuser@test.com");
        request.setNickname("新用户");
        request.setGender(1);
        request.setUserType(1);

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(2)
    @DisplayName("注册-用户名已存在")
    void testRegisterDuplicate() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("newuser");
        request.setPassword("pass123");
        request.setConfirmPassword("pass123");
        request.setEmail("another@test.com");
        request.setGender(1);
        request.setUserType(1);

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"));
    }

    @Test
    @Order(3)
    @DisplayName("注册-两次密码不一致")
    void testRegisterPasswordMismatch() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("user2");
        request.setPassword("pass123");
        request.setConfirmPassword("pass456");
        request.setEmail("user2@test.com");
        request.setGender(1);
        request.setUserType(1);

        mockMvc.perform(post("/user/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"));
    }

    @Test
    @Order(4)
    @DisplayName("登录成功")
    void testLoginSuccess() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("newuser");
        request.setPassword("pass123");

        MvcResult result = mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.roleType").value("USER"))
                .andExpect(jsonPath("$.data.userInfo.username").value("newuser"))
                .andReturn();

        // 提取token供后续测试使用
        String responseBody = result.getResponse().getContentAsString();
        Result<?> resultObj = objectMapper.readValue(responseBody, Result.class);
        var dataMap = (java.util.Map<String, Object>) resultObj.getData();
        token = (String) dataMap.get("token");
    }

    @Test
    @Order(5)
    @DisplayName("登录-用户名不存在")
    void testLoginUserNotFound() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("nonexistent");
        request.setPassword("pass123");

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"));
    }

    @Test
    @Order(6)
    @DisplayName("登录-密码错误")
    void testLoginWrongPassword() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername("newuser");
        request.setPassword("wrongpass");

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("500"));
    }

    @Test
    @Order(7)
    @DisplayName("登出")
    void testLogout() throws Exception {
        mockMvc.perform(post("/user/logout")
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));
    }

    @Test
    @Order(8)
    @DisplayName("未登录访问受保护接口")
    void testUnauthorizedAccess() throws Exception {
        mockMvc.perform(get("/user/some-protected-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("-1"));
    }
}
