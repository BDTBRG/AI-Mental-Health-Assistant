package com.amha.service.impl;

import com.amha.mapper.ConsultationContextSummaryOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
public class ContextSummaryMetrics {
    private final Counter completed;
    private final Counter failed;
    private final Counter contextPending;
    private final Counter contextUnavailable;
    private final Counter contextTooLarge;
    private final Timer duration;
    private final DistributionSummary inputChars;
    private final DistributionSummary outputChars;

    public ContextSummaryMetrics(MeterRegistry registry,
                                 ConsultationContextSummaryOutboxMapper outboxMapper) {
        Gauge.builder("amha.chat.context_summary.outbox.pending", outboxMapper,
                        mapper -> safeCount(mapper.countByStatus("PENDING")))
                .register(registry);
        Gauge.builder("amha.chat.context_summary.outbox.dead", outboxMapper,
                        mapper -> safeCount(mapper.countByStatus("DEAD")))
                .register(registry);
        Gauge.builder("amha.chat.context_summary.outbox.oldest_wait_seconds", outboxMapper,
                        mapper -> oldestWaitSeconds(mapper.selectOldestPendingAt()))
                .register(registry);
        this.completed = registry.counter("amha.chat.context_summary.completed");
        this.failed = registry.counter("amha.chat.context_summary.failed");
        this.contextPending = registry.counter("amha.chat.context_summary.context_pending");
        this.contextUnavailable = registry.counter("amha.chat.context_summary.context_unavailable");
        this.contextTooLarge = registry.counter("amha.chat.context_summary.context_too_large");
        this.duration = registry.timer("amha.chat.context_summary.duration");
        this.inputChars = DistributionSummary.builder("amha.chat.context_summary.input_chars")
                .register(registry);
        this.outputChars = DistributionSummary.builder("amha.chat.context_summary.output_chars")
                .register(registry);
    }

    public void recordCompleted(long durationMillis, int inputLength, int outputLength) {
        completed.increment();
        duration.record(Math.max(0, durationMillis), TimeUnit.MILLISECONDS);
        inputChars.record(Math.max(0, inputLength));
        outputChars.record(Math.max(0, outputLength));
    }

    public void recordFailed() {
        failed.increment();
    }

    public void recordContextPending() {
        contextPending.increment();
    }

    public void recordContextUnavailable() {
        contextUnavailable.increment();
    }

    public void recordContextTooLarge() {
        contextTooLarge.increment();
    }

    private static double safeCount(Long value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private static double oldestWaitSeconds(LocalDateTime createdAt) {
        if (createdAt == null) return 0.0;
        return Math.max(0, Duration.between(createdAt, LocalDateTime.now()).toMillis() / 1000.0);
    }
}
