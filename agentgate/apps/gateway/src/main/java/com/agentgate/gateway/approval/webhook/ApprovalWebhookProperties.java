package com.agentgate.gateway.approval.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Operator-controlled webhook notification configuration properties.
 * Disabled by default. Destination URL is never accepted from agent arguments or tool inputs.
 */
@ConfigurationProperties(prefix = "agentgate.approval.webhook")
public record ApprovalWebhookProperties(
        boolean enabled,
        String url,
        long connectTimeoutMs,
        long readTimeoutMs
) {
    public ApprovalWebhookProperties {
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 3000;
        }
        if (readTimeoutMs <= 0) {
            readTimeoutMs = 5000;
        }
    }

    public Duration getConnectTimeoutDuration() {
        return Duration.ofMillis(connectTimeoutMs);
    }

    public Duration getReadTimeoutDuration() {
        return Duration.ofMillis(readTimeoutMs);
    }
}
