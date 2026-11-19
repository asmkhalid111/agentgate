package com.agentgate.gateway.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for per-agent and per-tool requests-per-minute (RPM) rate limiting (FR6).
 * All configured RPM values must be strictly positive (> 0); zero or negative values fail startup fast.
 */
@ConfigurationProperties(prefix = "agentgate.ratelimit")
public class RateLimitProperties {

    /**
     * Whether request rate limiting is enabled.
     */
    private boolean enabled = true;

    /**
     * Default requests-per-minute limit per agent across all tools.
     */
    private int defaultAgentRpm = 60;

    /**
     * Default requests-per-minute limit per tool across all agents.
     */
    private int defaultToolRpm = 120;

    /**
     * Specific per-agent RPM limits (e.g. "agent-finance-bot" -> 30).
     */
    private Map<String, Integer> agentLimits = new HashMap<>();

    /**
     * Specific per-tool RPM limits (e.g. "orders_refund" -> 20).
     */
    private Map<String, Integer> toolLimits = new HashMap<>();

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (defaultAgentRpm <= 0) {
            throw new IllegalStateException("agentgate.ratelimit.default-agent-rpm must be positive (> 0), got: " + defaultAgentRpm);
        }
        if (defaultToolRpm <= 0) {
            throw new IllegalStateException("agentgate.ratelimit.default-tool-rpm must be positive (> 0), got: " + defaultToolRpm);
        }
        for (Map.Entry<String, Integer> entry : agentLimits.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalStateException("agentgate.ratelimit.agent-limits['" + entry.getKey() + "'] must be positive (> 0), got: " + entry.getValue());
            }
        }
        for (Map.Entry<String, Integer> entry : toolLimits.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalStateException("agentgate.ratelimit.tool-limits['" + entry.getKey() + "'] must be positive (> 0), got: " + entry.getValue());
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultAgentRpm() {
        return defaultAgentRpm;
    }

    public void setDefaultAgentRpm(int defaultAgentRpm) {
        this.defaultAgentRpm = defaultAgentRpm;
    }

    public int getDefaultToolRpm() {
        return defaultToolRpm;
    }

    public void setDefaultToolRpm(int defaultToolRpm) {
        this.defaultToolRpm = defaultToolRpm;
    }

    public Map<String, Integer> getAgentLimits() {
        return agentLimits;
    }

    public void setAgentLimits(Map<String, Integer> agentLimits) {
        this.agentLimits = agentLimits != null ? agentLimits : new HashMap<>();
    }

    public Map<String, Integer> getToolLimits() {
        return toolLimits;
    }

    public void setToolLimits(Map<String, Integer> toolLimits) {
        this.toolLimits = toolLimits != null ? toolLimits : new HashMap<>();
    }

    public int getAgentRpm(String agentId) {
        return agentLimits.getOrDefault(agentId, defaultAgentRpm);
    }

    public int getToolRpm(String toolName) {
        return toolLimits.getOrDefault(toolName, defaultToolRpm);
    }
}
