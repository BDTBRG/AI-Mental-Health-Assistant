package com.amha.service.impl;

import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextSummaryMetricsTest {

    @Test
    void registersBacklogAndResultMetricsWithoutUserOrSessionLabels() {
        ConsultationContextSummaryOutboxMapper mapper = mock(ConsultationContextSummaryOutboxMapper.class);
        when(mapper.countByStatus("PENDING")).thenReturn(3L);
        when(mapper.countByStatus("DEAD")).thenReturn(1L);
        when(mapper.selectOldestPendingAt()).thenReturn(LocalDateTime.now().minusSeconds(5));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ContextSummaryMetrics metrics = new ContextSummaryMetrics(registry, mapper);

        metrics.recordCompleted(120L, 800, 500);
        metrics.recordFailed();
        metrics.recordContextPending();
        metrics.recordContextUnavailable();
        metrics.recordContextTooLarge();

        assertEquals(3.0, registry.get("amha.chat.context_summary.outbox.pending").gauge().value());
        assertEquals(1.0, registry.get("amha.chat.context_summary.outbox.dead").gauge().value());
        assertTrue(registry.get("amha.chat.context_summary.outbox.oldest_wait_seconds").gauge().value() > 0);
        assertEquals(1.0, registry.get("amha.chat.context_summary.completed").counter().count());
        assertEquals(1.0, registry.get("amha.chat.context_summary.failed").counter().count());

        Set<String> tagKeys = registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey().toLowerCase())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(tagKeys.stream().noneMatch(key -> key.contains("user") || key.contains("session")));
    }
}
