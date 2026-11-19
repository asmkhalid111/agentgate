package com.agentgate.gateway.resilience.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for tool timeouts and circuit breakers (FR10).
 * Connect timeout, read timeout, execution deadline, failure threshold, and open wait duration
 * must be strictly positive (> 0). Invalid configurations fail fast during application startup.
 */
@ConfigurationProperties(prefix = "agentgate.resilience")
public class ResilienceProperties {

    /**
     * Default connection timeout for downstream tool requests.
     */
    private Duration defaultConnectTimeout = Duration.ofSeconds(3);

    /**
     * Default read/response timeout for downstream tool requests.
     */
    private Duration defaultReadTimeout = Duration.ofSeconds(5);

    /**
     * Default end-to-end execution deadline for downstream tool requests.
     */
    private Duration defaultExecutionDeadline = Duration.ofSeconds(10);

    /**
     * Number of consecutive downstream failures to trip the circuit breaker into OPEN state.
     */
    private int defaultFailureThreshold = 3;

    /**
     * Duration to remain in OPEN state before transitioning to HALF_OPEN to attempt a trial probe.
     */
    private Duration defaultOpenWaitDuration = Duration.ofSeconds(30);

    /**
     * Per-tool resilience overrides.
     */
    private Map<String, ToolResilienceConfig> tools = new HashMap<>();

    @PostConstruct
    public void validate() {
        validatePositive("defaultConnectTimeout", defaultConnectTimeout);
        validatePositive("defaultReadTimeout", defaultReadTimeout);
        validatePositive("defaultExecutionDeadline", defaultExecutionDeadline);
        if (defaultFailureThreshold <= 0) {
            throw new IllegalArgumentException("defaultFailureThreshold must be positive, got: " + defaultFailureThreshold);
        }
        validatePositive("defaultOpenWaitDuration", defaultOpenWaitDuration);

        if (tools != null) {
            for (Map.Entry<String, ToolResilienceConfig> entry : tools.entrySet()) {
                String toolName = entry.getKey();
                ToolResilienceConfig config = entry.getValue();
                if (config != null) {
                    if (config.getConnectTimeout() != null) {
                        validatePositive("connectTimeout for tool " + toolName, config.getConnectTimeout());
                    }
                    if (config.getReadTimeout() != null) {
                        validatePositive("readTimeout for tool " + toolName, config.getReadTimeout());
                    }
                    if (config.getExecutionDeadline() != null) {
                        validatePositive("executionDeadline for tool " + toolName, config.getExecutionDeadline());
                    }
                    if (config.getFailureThreshold() != null && config.getFailureThreshold() <= 0) {
                        throw new IllegalArgumentException("failureThreshold must be positive for tool " + toolName + ", got: " + config.getFailureThreshold());
                    }
                    if (config.getOpenWaitDuration() != null) {
                        validatePositive("openWaitDuration for tool " + toolName, config.getOpenWaitDuration());
                    }
                }
            }
        }
    }

    private void validatePositive(String fieldName, Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(fieldName + " must be a positive duration, got: " + duration);
        }
    }

    public Duration getConnectTimeout(String toolName) {
        if (toolName != null && tools != null && tools.containsKey(toolName)) {
            Duration custom = tools.get(toolName).getConnectTimeout();
            if (custom != null) {
                return custom;
            }
        }
        return defaultConnectTimeout;
    }

    public Duration getReadTimeout(String toolName) {
        if (toolName != null && tools != null && tools.containsKey(toolName)) {
            Duration custom = tools.get(toolName).getReadTimeout();
            if (custom != null) {
                return custom;
            }
        }
        return defaultReadTimeout;
    }

    public Duration getExecutionDeadline(String toolName) {
        if (toolName != null && tools != null && tools.containsKey(toolName)) {
            Duration custom = tools.get(toolName).getExecutionDeadline();
            if (custom != null) {
                return custom;
            }
        }
        return defaultExecutionDeadline;
    }

    public int getFailureThreshold(String toolName) {
        if (toolName != null && tools != null && tools.containsKey(toolName)) {
            Integer custom = tools.get(toolName).getFailureThreshold();
            if (custom != null) {
                return custom;
            }
        }
        return defaultFailureThreshold;
    }

    public Duration getOpenWaitDuration(String toolName) {
        if (toolName != null && tools != null && tools.containsKey(toolName)) {
            Duration custom = tools.get(toolName).getOpenWaitDuration();
            if (custom != null) {
                return custom;
            }
        }
        return defaultOpenWaitDuration;
    }

    public Duration getDefaultConnectTimeout() {
        return defaultConnectTimeout;
    }

    public void setDefaultConnectTimeout(Duration defaultConnectTimeout) {
        this.defaultConnectTimeout = defaultConnectTimeout;
    }

    public Duration getDefaultReadTimeout() {
        return defaultReadTimeout;
    }

    public void setDefaultReadTimeout(Duration defaultReadTimeout) {
        this.defaultReadTimeout = defaultReadTimeout;
    }

    public Duration getDefaultExecutionDeadline() {
        return defaultExecutionDeadline;
    }

    public void setDefaultExecutionDeadline(Duration defaultExecutionDeadline) {
        this.defaultExecutionDeadline = defaultExecutionDeadline;
    }

    public int getDefaultFailureThreshold() {
        return defaultFailureThreshold;
    }

    public void setDefaultFailureThreshold(int defaultFailureThreshold) {
        this.defaultFailureThreshold = defaultFailureThreshold;
    }

    public Duration getDefaultOpenWaitDuration() {
        return defaultOpenWaitDuration;
    }

    public void setDefaultOpenWaitDuration(Duration defaultOpenWaitDuration) {
        this.defaultOpenWaitDuration = defaultOpenWaitDuration;
    }

    public Map<String, ToolResilienceConfig> getTools() {
        return tools;
    }

    public void setTools(Map<String, ToolResilienceConfig> tools) {
        this.tools = tools;
    }

    public static class ToolResilienceConfig {
        private Duration connectTimeout;
        private Duration readTimeout;
        private Duration executionDeadline;
        private Integer failureThreshold;
        private Duration openWaitDuration;

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Duration getExecutionDeadline() {
            return executionDeadline;
        }

        public void setExecutionDeadline(Duration executionDeadline) {
            this.executionDeadline = executionDeadline;
        }

        public Integer getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(Integer failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenWaitDuration() {
            return openWaitDuration;
        }

        public void setOpenWaitDuration(Duration openWaitDuration) {
            this.openWaitDuration = openWaitDuration;
        }
    }
}
