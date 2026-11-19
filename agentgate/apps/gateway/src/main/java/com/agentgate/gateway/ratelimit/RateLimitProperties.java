package com.agentgate.gateway.ratelimit;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for per-agent and per-tool requests-per-minute (RPM) rate limiting,
 * daily quotas, and rolling hourly write cap (FR6).
 * All configured limit values must be strictly positive (> 0); hourly write caps cannot exceed 20.
 * Invalid configurations fail fast during application startup.
 */
@ConfigurationProperties(prefix = "agentgate.ratelimit")
public class RateLimitProperties {

    /**
     * Whether request rate limiting and quotas are enabled.
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

    /**
     * Default daily request quota per agent across all tools.
     */
    private int defaultAgentDailyQuota = 10000;

    /**
     * Default daily request quota per tool across all agents.
     */
    private int defaultToolDailyQuota = 50000;

    /**
     * Specific per-agent daily quotas (e.g. "agent-finance-bot" -> 5000).
     */
    private Map<String, Integer> agentDailyQuotas = new HashMap<>();

    /**
     * Specific per-tool daily quotas (e.g. "orders_refund" -> 1000).
     */
    private Map<String, Integer> toolDailyQuotas = new HashMap<>();

    /**
     * Default hourly write dispatch cap per agent across all write tools.
     * Hard upper bound is 20 (FR6 blast-radius cap).
     */
    private int defaultHourlyWriteCap = 20;

    /**
     * Specific per-agent hourly write dispatch caps (e.g. "agent-finance-bot" -> 15).
     * Cannot exceed 20.
     */
    private Map<String, Integer> agentHourlyWriteCaps = new HashMap<>();

    /**
     * Percentage threshold at which warning alerts are triggered (default 80%).
     */
    private int warningThresholdPercent = 80;

    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }

        // 1. Validate RPM limits
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

        // 2. Validate Daily Quotas
        if (defaultAgentDailyQuota <= 0) {
            throw new IllegalStateException("agentgate.ratelimit.default-agent-daily-quota must be positive (> 0), got: " + defaultAgentDailyQuota);
        }
        if (defaultToolDailyQuota <= 0) {
            throw new IllegalStateException("agentgate.ratelimit.default-tool-daily-quota must be positive (> 0), got: " + defaultToolDailyQuota);
        }
        for (Map.Entry<String, Integer> entry : agentDailyQuotas.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalStateException("agentgate.ratelimit.agent-daily-quotas['" + entry.getKey() + "'] must be positive (> 0), got: " + entry.getValue());
            }
        }
        for (Map.Entry<String, Integer> entry : toolDailyQuotas.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) {
                throw new IllegalStateException("agentgate.ratelimit.tool-daily-quotas['" + entry.getKey() + "'] must be positive (> 0), got: " + entry.getValue());
            }
        }

        // 3. Validate Hourly Write Cap: Effective max is 20; values above 20, zero or negative are strictly rejected
        if (defaultHourlyWriteCap <= 0 || defaultHourlyWriteCap > 20) {
            throw new IllegalStateException("agentgate.ratelimit.default-hourly-write-cap must be between 1 and 20, got: " + defaultHourlyWriteCap);
        }
        for (Map.Entry<String, Integer> entry : agentHourlyWriteCaps.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0 || entry.getValue() > 20) {
                throw new IllegalStateException("agentgate.ratelimit.agent-hourly-write-caps['" + entry.getKey() + "'] must be between 1 and 20, got: " + entry.getValue());
            }
        }

        // 4. Validate Alert Warning Threshold
        if (warningThresholdPercent <= 0 || warningThresholdPercent >= 100) {
            throw new IllegalStateException("agentgate.ratelimit.warning-threshold-percent must be between 1 and 99, got: " + warningThresholdPercent);
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

    public int getDefaultAgentDailyQuota() {
        return defaultAgentDailyQuota;
    }

    public void setDefaultAgentDailyQuota(int defaultAgentDailyQuota) {
        this.defaultAgentDailyQuota = defaultAgentDailyQuota;
    }

    public int getDefaultToolDailyQuota() {
        return defaultToolDailyQuota;
    }

    public void setDefaultToolDailyQuota(int defaultToolDailyQuota) {
        this.defaultToolDailyQuota = defaultToolDailyQuota;
    }

    public Map<String, Integer> getAgentDailyQuotas() {
        return agentDailyQuotas;
    }

    public void setAgentDailyQuotas(Map<String, Integer> agentDailyQuotas) {
        this.agentDailyQuotas = agentDailyQuotas != null ? agentDailyQuotas : new HashMap<>();
    }

    public Map<String, Integer> getToolDailyQuotas() {
        return toolDailyQuotas;
    }

    public void setToolDailyQuotas(Map<String, Integer> toolDailyQuotas) {
        this.toolDailyQuotas = toolDailyQuotas != null ? toolDailyQuotas : new HashMap<>();
    }

    public int getDefaultHourlyWriteCap() {
        return defaultHourlyWriteCap;
    }

    public void setDefaultHourlyWriteCap(int defaultHourlyWriteCap) {
        this.defaultHourlyWriteCap = defaultHourlyWriteCap;
    }

    public Map<String, Integer> getAgentHourlyWriteCaps() {
        return agentHourlyWriteCaps;
    }

    public void setAgentHourlyWriteCaps(Map<String, Integer> agentHourlyWriteCaps) {
        this.agentHourlyWriteCaps = agentHourlyWriteCaps != null ? agentHourlyWriteCaps : new HashMap<>();
    }

    public int getWarningThresholdPercent() {
        return warningThresholdPercent;
    }

    public void setWarningThresholdPercent(int warningThresholdPercent) {
        this.warningThresholdPercent = warningThresholdPercent;
    }

    public int getAgentRpm(String agentId) {
        return agentLimits.getOrDefault(agentId, defaultAgentRpm);
    }

    public int getToolRpm(String toolName) {
        return toolLimits.getOrDefault(toolName, defaultToolRpm);
    }

    public int getAgentDailyQuota(String agentId) {
        return agentDailyQuotas.getOrDefault(agentId, defaultAgentDailyQuota);
    }

    public int getToolDailyQuota(String toolName) {
        return toolDailyQuotas.getOrDefault(toolName, defaultToolDailyQuota);
    }

    public int getAgentHourlyWriteCap(String agentId) {
        return agentHourlyWriteCaps.getOrDefault(agentId, defaultHourlyWriteCap);
    }
}
