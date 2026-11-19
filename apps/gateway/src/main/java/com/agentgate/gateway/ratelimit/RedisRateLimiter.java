package com.agentgate.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Redis-backed atomic rate limiter and quota enforcer (FR6).
 * <p>
 * Evaluates four dimensions atomically at ingress:
 * <ul>
 *   <li><b>Agent RPM:</b> Fixed 60-second calendar window.</li>
 *   <li><b>Tool RPM:</b> Fixed 60-second calendar window.</li>
 *   <li><b>Agent Daily Quota:</b> Calendar day UTC bucket (resets at midnight UTC).</li>
 *   <li><b>Tool Daily Quota:</b> Calendar day UTC bucket (resets at midnight UTC).</li>
 * </ul>
 * <p>
 * Invariant & Safety Guarantees:
 * <ul>
 *   <li><b>All-or-Nothing Atomic Gate:</b> If any limit is exceeded, none of the 4 counters increment.</li>
 *   <li><b>Fail-Closed on Outage:</b> Unreachable Redis rejects unmetered requests with a generic error.</li>
 *   <li><b>Atomic Alert Deduplication:</b> 80% warning and hard limit breach alerts are deduplicated via Redis SET NX.</li>
 *   <li><b>Zero Sensitive Data Leak:</b> Client responses never leak Redis hosts, credentials, keys, or stack traces.</li>
 * </ul>
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final Logger alertLog = LoggerFactory.getLogger("com.agentgate.gateway.ratelimit.alert");

    private static final String RPM_EXCEEDED_MESSAGE =
            "RATE_LIMIT_EXCEEDED: Agent or tool request limit exceeded. Retry after the current window resets.";
    private static final String AGENT_DAILY_EXCEEDED_MESSAGE =
            "RATE_LIMIT_EXCEEDED: Daily request quota exceeded. Retry after midnight UTC.";
    private static final String TOOL_DAILY_EXCEEDED_MESSAGE =
            "RATE_LIMIT_EXCEEDED: Daily tool request quota exceeded. Retry after midnight UTC.";
    private static final String UNAVAILABLE_MESSAGE =
            "RATE_LIMIT_UNAVAILABLE: Rate limiting is temporarily unavailable.";

    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.]+$");
    private static final long RPM_WINDOW_MS = 60_000L;
    private static final long RPM_KEY_TTL_SECONDS = 120L;
    private static final long DAILY_KEY_TTL_SECONDS = 172_800L; // 48 hours for clean daily eviction

    private static final String FOUR_DIMENSION_LUA_SCRIPT = """
            local agent_rpm_val = redis.call('GET', KEYS[1])
            local tool_rpm_val = redis.call('GET', KEYS[2])
            local agent_daily_val = redis.call('GET', KEYS[3])
            local tool_daily_val = redis.call('GET', KEYS[4])

            local agent_rpm = agent_rpm_val and tonumber(agent_rpm_val) or 0
            local tool_rpm = tool_rpm_val and tonumber(tool_rpm_val) or 0
            local agent_daily = agent_daily_val and tonumber(agent_daily_val) or 0
            local tool_daily = tool_daily_val and tonumber(tool_daily_val) or 0

            local agent_rpm_limit = tonumber(ARGV[1])
            local tool_rpm_limit = tonumber(ARGV[2])
            local agent_daily_limit = tonumber(ARGV[3])
            local tool_daily_limit = tonumber(ARGV[4])
            local rpm_ttl = tonumber(ARGV[5])
            local daily_ttl = tonumber(ARGV[6])
            local warn_percent = tonumber(ARGV[7])

            -- 1. Check all limits before incrementing anything
            if agent_rpm >= agent_rpm_limit then
                return 'AGENT_RPM_EXCEEDED'
            end

            if tool_rpm >= tool_rpm_limit then
                return 'TOOL_RPM_EXCEEDED'
            end

            if agent_daily >= agent_daily_limit then
                local hard_alert = redis.call('SET', KEYS[7], '1', 'EX', daily_ttl, 'NX')
                if hard_alert then
                    return 'AGENT_DAILY_EXCEEDED:ALERT'
                else
                    return 'AGENT_DAILY_EXCEEDED'
                end
            end

            if tool_daily >= tool_daily_limit then
                local hard_alert = redis.call('SET', KEYS[8], '1', 'EX', daily_ttl, 'NX')
                if hard_alert then
                    return 'TOOL_DAILY_EXCEEDED:ALERT'
                else
                    return 'TOOL_DAILY_EXCEEDED'
                end
            end

            -- 2. Atomically increment all 4 counters
            local new_agent_rpm = redis.call('INCR', KEYS[1])
            if new_agent_rpm == 1 then redis.call('EXPIRE', KEYS[1], rpm_ttl) end

            local new_tool_rpm = redis.call('INCR', KEYS[2])
            if new_tool_rpm == 1 then redis.call('EXPIRE', KEYS[2], rpm_ttl) end

            local new_agent_daily = redis.call('INCR', KEYS[3])
            if new_agent_daily == 1 then redis.call('EXPIRE', KEYS[3], daily_ttl) end

            local new_tool_daily = redis.call('INCR', KEYS[4])
            if new_tool_daily == 1 then redis.call('EXPIRE', KEYS[4], daily_ttl) end

            local alert_result = 'OK'

            -- 3. Check 80% warning alerts
            local agent_warn_thresh = math.ceil(agent_daily_limit * warn_percent / 100)
            if new_agent_daily >= agent_warn_thresh then
                local warn_alert = redis.call('SET', KEYS[5], '1', 'EX', daily_ttl, 'NX')
                if warn_alert then
                    alert_result = alert_result .. ':AGENT_DAILY_WARN'
                end
            end

            local tool_warn_thresh = math.ceil(tool_daily_limit * warn_percent / 100)
            if new_tool_daily >= tool_warn_thresh then
                local warn_alert = redis.call('SET', KEYS[6], '1', 'EX', daily_ttl, 'NX')
                if warn_alert then
                    alert_result = alert_result .. ':TOOL_DAILY_WARN'
                end
            end

            return alert_result
            """;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final DefaultRedisScript<String> redisScript;
    private final Clock clock;

    @Autowired
    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, Clock.systemUTC());
    }

    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.redisScript = new DefaultRedisScript<>(FOUR_DIMENSION_LUA_SCRIPT, String.class);
    }

    @Override
    public RateLimitDecision tryAcquire(String agentId, String toolName) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow();
        }

        if (!isValidIdentifier(agentId) || !isValidIdentifier(toolName)) {
            log.warn("Rate limit check rejected due to invalid identifier: agentId='{}', toolName='{}'", agentId, toolName);
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
        }

        int agentRpm = properties.getAgentRpm(agentId);
        int toolRpm = properties.getToolRpm(toolName);
        int agentDailyQuota = properties.getAgentDailyQuota(agentId);
        int toolDailyQuota = properties.getToolDailyQuota(toolName);

        if (agentRpm <= 0 || toolRpm <= 0 || agentDailyQuota <= 0 || toolDailyQuota <= 0) {
            log.error("Invalid rate limits or daily quotas configured for agent='{}' or tool='{}'", agentId, toolName);
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
        }

        long minuteBucket = clock.millis() / RPM_WINDOW_MS;
        String dayBucket = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();

        String agentRpmKey = "agentgate:ratelimit:agent:" + agentId + ":" + minuteBucket;
        String toolRpmKey = "agentgate:ratelimit:tool:" + toolName + ":" + minuteBucket;
        String agentDailyKey = "agentgate:quota:daily:agent:" + agentId + ":" + dayBucket;
        String toolDailyKey = "agentgate:quota:daily:tool:" + toolName + ":" + dayBucket;

        String agentWarnKey = "agentgate:alert:quota:agent:" + agentId + ":" + dayBucket + ":warn";
        String toolWarnKey = "agentgate:alert:quota:tool:" + toolName + ":" + dayBucket + ":warn";
        String agentHardKey = "agentgate:alert:quota:agent:" + agentId + ":" + dayBucket + ":hard";
        String toolHardKey = "agentgate:alert:quota:tool:" + toolName + ":" + dayBucket + ":hard";

        try {
            String result = redisTemplate.execute(
                    redisScript,
                    List.of(agentRpmKey, toolRpmKey, agentDailyKey, toolDailyKey,
                            agentWarnKey, toolWarnKey, agentHardKey, toolHardKey),
                    String.valueOf(agentRpm),
                    String.valueOf(toolRpm),
                    String.valueOf(agentDailyQuota),
                    String.valueOf(toolDailyQuota),
                    String.valueOf(RPM_KEY_TTL_SECONDS),
                    String.valueOf(DAILY_KEY_TTL_SECONDS),
                    String.valueOf(properties.getWarningThresholdPercent())
            );

            if (result == null) {
                log.error("Redis rate limiter Lua script returned null for agent='{}', tool='{}'", agentId, toolName);
                return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
            }

            if (result.startsWith("OK")) {
                if (result.contains("AGENT_DAILY_WARN")) {
                    alertLog.warn("[RATE_LIMIT_ALERT] severity=WARN type=DAILY_QUOTA_WARNING agent='{}' limit={} threshold={}%",
                            agentId, agentDailyQuota, properties.getWarningThresholdPercent());
                }
                if (result.contains("TOOL_DAILY_WARN")) {
                    alertLog.warn("[RATE_LIMIT_ALERT] severity=WARN type=DAILY_QUOTA_WARNING tool='{}' limit={} threshold={}%",
                            toolName, toolDailyQuota, properties.getWarningThresholdPercent());
                }
                return RateLimitDecision.allow();
            }

            if ("AGENT_RPM_EXCEEDED".equals(result) || "TOOL_RPM_EXCEEDED".equals(result)) {
                log.info("Rate limit exceeded [decision={}]: agent='{}' (rpmLimit={}), tool='{}' (rpmLimit={})",
                        result, agentId, agentRpm, toolName, toolRpm);
                return RateLimitDecision.exceed(RPM_EXCEEDED_MESSAGE);
            }

            if (result.startsWith("AGENT_DAILY_EXCEEDED")) {
                if (result.contains(":ALERT")) {
                    alertLog.error("[RATE_LIMIT_ALERT] severity=ERROR type=DAILY_QUOTA_BREACH agent='{}' limit={}",
                            agentId, agentDailyQuota);
                }
                log.info("Daily quota exceeded for agent='{}' (dailyQuota={})", agentId, agentDailyQuota);
                return RateLimitDecision.exceed(AGENT_DAILY_EXCEEDED_MESSAGE);
            }

            if (result.startsWith("TOOL_DAILY_EXCEEDED")) {
                if (result.contains(":ALERT")) {
                    alertLog.error("[RATE_LIMIT_ALERT] severity=ERROR type=DAILY_QUOTA_BREACH tool='{}' limit={}",
                            toolName, toolDailyQuota);
                }
                log.info("Daily quota exceeded for tool='{}' (dailyQuota={})", toolName, toolDailyQuota);
                return RateLimitDecision.exceed(TOOL_DAILY_EXCEEDED_MESSAGE);
            }

            log.warn("Unexpected result from rate limiter script: '{}'", result);
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);

        } catch (Exception e) {
            log.error("Redis rate limiter unavailable: {}", e.getMessage());
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
        }
    }

    private boolean isValidIdentifier(String s) {
        return s != null && !s.isBlank() && SAFE_KEY_PATTERN.matcher(s.trim()).matches();
    }
}
