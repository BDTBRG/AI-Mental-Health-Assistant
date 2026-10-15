package com.amha.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "amha.chat.context-summary")
public class ChatContextSummaryProperties {
    private boolean preflightEnabled;
    private boolean workerEnabled;
    private boolean agentEnabled;
    private int maxHistoryMessages = 39;
    private int recentMessagesToKeep = 12;
    private int maxContextChars = 32_000;
    private int maxSingleMessageChars = 8_000;
    private int maxAgentRequestBytes = 65_536;
    private long retryAfterMs = 5_000;
}
