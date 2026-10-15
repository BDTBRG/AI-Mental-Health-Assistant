package com.amha.service.impl;

import com.amha.config.ChatContextSummaryProperties;
import com.amha.dto.ChatContextPreparation;
import com.amha.entity.ConsultationContextSummary;
import com.amha.entity.ConsultationContextSummaryOutbox;
import com.amha.entity.ConsultationMessage;
import com.amha.mapper.ConsultationContextSummaryMapper;
import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import com.amha.mapper.ConsultationMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatContextAssemblerTest {
    private static final long SESSION_ID = 100L;
    private static final long USER_ID = 7L;

    @Mock
    private ConsultationMessageMapper messageMapper;
    @Mock
    private ConsultationContextSummaryMapper summaryMapper;
    @Mock
    private ConsultationContextSummaryOutboxMapper outboxMapper;

    private ChatContextAssembler assembler;

    @BeforeEach
    void setUp() {
        ChatContextSummaryProperties properties = new ChatContextSummaryProperties();
        properties.setPreflightEnabled(true);
        properties.setWorkerEnabled(true);
        properties.setAgentEnabled(true);
        assembler = new ChatContextAssembler(messageMapper, summaryMapper, outboxMapper,
                new ObjectMapper(), properties, true);
    }

    @Test
    void returnsOnlyOwnedMessagesAfterSummaryWatermarkInAscendingOrder() {
        ConsultationContextSummary summary = summary(3L, "{\"userStatedContext\":[],\"userPreferences\":[],\"openTopics\":[]}");
        when(summaryMapper.selectBySessionAndUser(SESSION_ID, USER_ID)).thenReturn(summary);
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(8L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 3L, 41))
                .thenReturn(List.of(message(8L, SESSION_ID, 2, "assistant"),
                        message(6L, SESSION_ID + 1, 1, "foreign session"),
                        message(5L, SESSION_ID, 1, "older raw"),
                        message(4L, SESSION_ID, 2, "after watermark")));

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "new input", false);

        assertEquals(ChatContextPreparation.Status.READY, result.status());
        assertEquals(List.of(4L, 5L, 8L), result.history().stream().map(ConsultationMessage::getId).toList());
        assertEquals(List.of(), result.contextSummary().get("userStatedContext"));
        assertEquals(8L, result.throughMessageId());
        assertEquals(8L, result.observedLatestMessageId());
        verify(messageMapper).selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 3L, 41);
    }

    @Test
    void refusesReadySnapshotWhenLatestMessageReadChangesDuringAssembly() {
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(8L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41))
                .thenReturn(List.of(message(7L, SESSION_ID, 2, "newer message arrived between reads")));

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "current", false);

        assertEquals(ChatContextPreparation.Status.SUMMARY_UNAVAILABLE, result.status());
    }

    @Test
    void keepsThirtyNineRawHistoryMessagesAndCurrentInputWithinMessageLimit() {
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(39L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41))
                .thenReturn(messages(39, 1, 20));

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "current", false);

        assertEquals(ChatContextPreparation.Status.READY, result.status());
        assertEquals(39, result.history().size());
        assertFalse(result.history().stream().anyMatch(message -> "current".equals(message.getContent())));
    }

    @Test
    void compactsOnlyMessagesOlderThanTheElevenRawMessagesKeptBesideCurrentInput() {
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(40L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41))
                .thenReturn(messages(40, 1, 20));

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "current", false);

        assertEquals(ChatContextPreparation.Status.SUMMARY_PENDING, result.status());
        assertEquals(29L, result.summaryTargetThroughMessageId());
        assertTrue(result.retryAfterMs() > 0);
    }

    @Test
    void overBudgetOlderHistoryRequestsCompactionWhenTheRecentWindowFits() {
        List<ConsultationMessage> rows = new ArrayList<>();
        for (int id = 1; id <= 13; id++) {
            rows.add(message(id, SESSION_ID, id % 2 == 0 ? 2 : 1, "x".repeat(2_700)));
        }
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(13L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41)).thenReturn(rows);

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "current", false);

        assertEquals(ChatContextPreparation.Status.SUMMARY_PENDING, result.status());
        assertEquals(2L, result.summaryTargetThroughMessageId());
    }

    @Test
    void rejectsARecentSingleMessageOverEightThousandCharacters() {
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(1L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41))
                .thenReturn(List.of(message(1L, SESSION_ID, 1, "x".repeat(8_001))));

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "current", false);

        assertEquals(ChatContextPreparation.Status.WINDOW_TOO_LARGE, result.status());
    }

    @Test
    void rejectsWhenTheTwelveMessageRecentWindowExceedsTheTotalCharacterBudget() {
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(11L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41))
                .thenReturn(messages(11, 1, 3_000));

        ChatContextPreparation result = assembler.prepare(SESSION_ID, USER_ID, "y".repeat(3_000), false);

        assertEquals(ChatContextPreparation.Status.WINDOW_TOO_LARGE, result.status());
    }

    @Test
    void reportsUnavailableWhenCompactionIsDisabledOrDead() {
        when(messageMapper.selectMaxMessageIdForOwner(SESSION_ID, USER_ID)).thenReturn(40L);
        when(messageMapper.selectLatestAfterWatermarkForOwner(SESSION_ID, USER_ID, 0L, 41))
                .thenReturn(messages(40, 1, 20));
        ChatContextSummaryProperties disabled = new ChatContextSummaryProperties();
        disabled.setPreflightEnabled(false);
        disabled.setWorkerEnabled(true);
        disabled.setAgentEnabled(true);
        ChatContextAssembler disabledAssembler = new ChatContextAssembler(messageMapper, summaryMapper,
                outboxMapper, new ObjectMapper(), disabled, true);

        assertEquals(ChatContextPreparation.Status.SUMMARY_UNAVAILABLE,
                disabledAssembler.prepare(SESSION_ID, USER_ID, "current", false).status());

        ConsultationContextSummaryOutbox dead = new ConsultationContextSummaryOutbox();
        dead.setStatus("DEAD");
        when(outboxMapper.selectBySessionAndUser(SESSION_ID, USER_ID)).thenReturn(dead);
        assertEquals(ChatContextPreparation.Status.SUMMARY_UNAVAILABLE,
                assembler.prepare(SESSION_ID, USER_ID, "current", false).status());
    }

    @Test
    void rejectsConfigurationThatCouldSilentlyDropHistoryBeyondTheQueryWindow() {
        ChatContextSummaryProperties invalid = new ChatContextSummaryProperties();
        invalid.setMaxHistoryMessages(41);
        ChatContextAssembler invalidAssembler = new ChatContextAssembler(messageMapper, summaryMapper,
                outboxMapper, new ObjectMapper(), invalid, true);

        assertThrows(IllegalStateException.class, invalidAssembler::validateConfiguration);
    }

    @Test
    void rejectsRecentWindowLargerThanConfiguredRawHistoryBudget() {
        ChatContextSummaryProperties invalid = new ChatContextSummaryProperties();
        invalid.setMaxHistoryMessages(10);
        invalid.setRecentMessagesToKeep(12);
        ChatContextAssembler invalidAssembler = new ChatContextAssembler(messageMapper, summaryMapper,
                outboxMapper, new ObjectMapper(), invalid, true);

        assertThrows(IllegalStateException.class, invalidAssembler::validateConfiguration);
    }

    private static ConsultationContextSummary summary(long watermark, String json) {
        ConsultationContextSummary summary = new ConsultationContextSummary();
        summary.setSessionId(SESSION_ID);
        summary.setUserId(USER_ID);
        summary.setCoveredThroughMessageId(watermark);
        summary.setSummaryVersion(1L);
        summary.setPromptVersion("context-summary-v1");
        summary.setSummaryJson(json);
        return summary;
    }

    private static List<ConsultationMessage> messages(int count, long startId, int chars) {
        List<ConsultationMessage> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(message(startId + index, SESSION_ID, index % 2 == 0 ? 1 : 2, "m".repeat(chars)));
        }
        return rows;
    }

    private static ConsultationMessage message(long id, long sessionId, int senderType, String content) {
        ConsultationMessage message = new ConsultationMessage();
        message.setId(id);
        message.setSessionId(sessionId);
        message.setSenderType(senderType);
        message.setContent(content);
        return message;
    }
}
