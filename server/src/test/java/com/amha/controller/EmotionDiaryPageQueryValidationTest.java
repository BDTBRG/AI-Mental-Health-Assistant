package com.amha.controller;

import com.amha.common.GlobalExceptionHandler;
import com.amha.dto.EmotionDiaryPageQuery;
import com.amha.service.EmotionDiaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EmotionDiaryPageQueryValidationTest {

    private EmotionDiaryService emotionDiaryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        emotionDiaryService = mock(EmotionDiaryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new EmotionDiaryController(emotionDiaryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String diaryRequestBody(int moodScore) {
        return """
                {
                  "diaryContent": "今天完成了重要的事情",
                  "diaryDate": "2026-10-14",
                  "dominantEmotion": "开心",
                  "emotionTriggers": "项目完成",
                  "moodScore": %d
                }
                """.formatted(moodScore);
    }

    @Test
    void rejectsNonNumericUserIdAsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("userId", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(emotionDiaryService);
    }

    @Test
    void rejectsNonPositiveUserIdAsBadRequestWithoutCallingService() throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("userId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(emotionDiaryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "2147483648", "999999999999999999999"})
    void rejectsInvalidCurrentPageAsBadRequestWithoutCallingService(String currentPage) throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("currentPage", currentPage))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(emotionDiaryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "999999999999999999999"})
    void rejectsInvalidSizeAsBadRequestWithoutCallingService(String size) throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(emotionDiaryService);
    }

    @ParameterizedTest
    @CsvSource({"0,10", "1,11", "7,4"})
    void rejectsInvalidMoodScoreRangeAsBadRequestWithoutCallingService(String minMoodScore, String maxMoodScore)
            throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("minMoodScore", minMoodScore)
                        .param("maxMoodScore", maxMoodScore))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(emotionDiaryService);
    }

    @Test
    void acceptsMinimumPageAndSizeAndMaximumSize() throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("currentPage", "1")
                        .param("size", "1"))
                .andExpect(status().isOk());

        verify(emotionDiaryService).getAdminPage(
                argThat(query -> query.getCurrentPage() == 1 && query.getSize() == 1), eq(1L));

        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("currentPage", "2147483647")
                        .param("size", "100"))
                .andExpect(status().isOk());

        verify(emotionDiaryService).getAdminPage(
                argThat(query -> query.getCurrentPage() == Integer.MAX_VALUE && query.getSize() == 100), eq(1L));
    }

    @Test
    void acceptsMoodScoreRangeFromOneToTen() throws Exception {
        mockMvc.perform(get("/emotion-diary/admin/page")
                        .requestAttr("userType", 2)
                        .requestAttr("userId", 1L)
                        .param("minMoodScore", "1")
                        .param("maxMoodScore", "10"))
                .andExpect(status().isOk());

        verify(emotionDiaryService).getAdminPage(
                argThat(query -> query.getMinMoodScore() == 1 && query.getMaxMoodScore() == 10), eq(1L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11})
    void rejectsMoodScoreOutsideOneToTenOnDiarySubmission(int moodScore) throws Exception {
        mockMvc.perform(post("/emotion-diary")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diaryRequestBody(moodScore)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(emotionDiaryService);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void acceptsMoodScoreBoundariesOnDiarySubmission(int moodScore) throws Exception {
        mockMvc.perform(post("/emotion-diary")
                        .requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diaryRequestBody(moodScore)))
                .andExpect(status().isOk());

        verify(emotionDiaryService).submitDiary(
                argThat(request -> request.getMoodScore() == moodScore), eq(1L));
    }
}
