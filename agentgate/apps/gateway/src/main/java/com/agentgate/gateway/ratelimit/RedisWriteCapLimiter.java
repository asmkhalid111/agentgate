package com.agentgate.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Redis-backed atomic write-cap limiter enforcing at most 20 write dispatches per agent
 * within any rolling 60-minute window using a Redis Sorted Set (ZSET) and atomic Lua script (FR6).
 * <p>
 * Invariant & Safety Guarantees:
 * <ul>
 *   <li><b>Rolling Window:</b> Removes entries older than 3,600,000 ms before counting active dispatches.</li>
 *   <li><b>Atomic Admission:</b> If count &gt;= limit, disallows insertion and rejects without modifying state.</li>
 *   <li><b>Unique Members:</b> Dispatches receive a millisecond-stamped UUID member to prevent collision.</li>
 *   <li><b>Fail-Closed on Outage:</b> Unreachable Redis prevents downstream execution with a safe error message.</li>
 *   <li><b>Atomic Alert Deduplication:</b> Emits 80% warning and hard breach alerts at most once per hour.</li>
 * </ul>
 */
@Component
public class RedisWriteCapLimiter implements WriteCapLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisWriteCapLimiter.class);
    private static final Logger alertLog = LoggerFactory.getLogger("com.agentgate.gateway.ratelimit.alert");

    private static final String EXCEEDED_MESSAGE =
            "WRITE_CAP_EXCEEDED: Hourly write dispatch cap reached. Maximum 20 write operations per rolling hour.";
    private static final String UNAVAILABLE_MESSAGE =
            "RATE_LIMIT_UNAVAILABLE: Rate limiting is temporarily unavailable.";

    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.]+$");
    private static final long ROLLING_WINDOW_MS = 3_600_000L;
    private static final long KEY_TTL_SECONDS = 7_200L;
    private static final long ALERT_TTL_SECONDS = 3_600L;

    private static final String ROLLING_ZSET_LUA_SCRIPT = """
            local zset_key = KEYS[1]
            local alert_warn_key = KEYS[2]
            local alert_hard_key = KEYS[3]

            local now_ms = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member_id = ARGV[4]
            local ttl_seconds = tonumber(ARGV[5])
            local warn_percent = tonumber(ARGV[6])

            -- 1. Evict entries outside the rolling window
            local cutoff_ms = now_ms - window_ms
            redis.call('ZREMRANGEBYSCORE', zset_key, '-inf', '(' .. cutoff_ms)

            -- 2. Count active dispatches in the rolling window
            local current_count = redis.call('ZCARD', zset_key)

            -- 3. Reject if count is at or above the configured cap
            if current_count >= limit then
                local hard_alert = redis.call('SET', alert_hard_key, '1', 'EX', 3600, 'NX')
                if hard_alert then
                    return 'WRITE_CAP_EXCEEDED:ALERT'
                else
                    return 'WRITE_CAP_EXCEEDED'
                end
            end

            -- 4. Record new dispatch attempt with score = now_ms
            redis.call('ZADD', zset_key, now_ms, member_id)
            redis.call('EXPIRE', zset_key, ttl_seconds)

            local new_count = current_count + 1
            local alert_result = 'OK'

            -- 5. Atomic check for 80% warning alert threshold
            local warn_threshold = math.ceil(limit * warn_percent / 100)
            if new_count >= warn_threshold then
                local warn_alert = redis.call('SET', alert_warn_key, '1', 'EX', 3600, 'NX')
                if warn_alert then
                    alert_result = 'OK:WRITE_CAP_WARN'
                end
            end

            return alert_result
            """;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final DefaultRedisScript<String> redisScript;
    private final Clock clock;

    @Autowired
    public RedisWriteCapLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, Clock.systemUTC());
    }

    public RedisWriteCapLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.redisScript = new DefaultRedisScript<>(ROLLING_ZSET_LUA_SCRIPT, String.class);
    }

    @Override
    public WriteCapDecision tryAcquireWrite(String agentId) {
        if (!properties.isEnabled()) {
            return WriteCapDecision.allow();
        }

        if (!isValidIdentifier(agentId)) {
            log.warn("Write cap check rejected due to invalid agent identifier: '{}'", agentId);
            return WriteCapDecision.unavailable(UNAVAILABLE_MESSAGE);
        }

        int limit = properties.getAgentHourlyWriteCap(agentId);
        if (limit <= 0 || limit > 20) {
            log.error("Invalid hourly write cap configured for agent='{}': {}", agentId, limit);
            return WriteCapDecision.unavailable(UNAVAILABLE_MESSAGE);
        }

        long nowMs = clock.millis();
        String zsetKey = "agentgate:writecap:agent:" + agentId;
        String alertWarnKey = "agentgate:alert:writecap:agent:" + agentId + ":warn";
        String alertHardKey = "agentgate:alert:writecap:agent:" + agentId + ":hard";
        String memberId = nowMs + ":" + UUID.randomUUID();

        try {
            String result = redisTemplate.execute(
                    redisScript,
                    List.of(zsetKey, alertWarnKey, alertHardKey),
                    String.valueOf(nowMs),
                    String.valueOf(ROLLING_WINDOW_MS),
                    String.valueOf(limit),
                    memberId,
                    String.valueOf(KEY_TTL_SECONDS),
                    String.valueOf(properties.getWarningThresholdPercent())
            );

            if (result == null) {
                log.error("Redis write cap Lua script returned null result for agent='{}'", agentId);
                return WriteCapDecision.unavailable(UNAVAILABLE_MESSAGE);
            }

            if (result.startsWith("OK")) {
                if (result.contains("WRITE_CAP_WARN")) {
                    alertLog.warn("[RATE_LIMIT_ALERT] severity=WARN type=WRITE_CAP_WARNING agent='{}' limit={} threshold={}%",
                            agentId, limit, properties.getWarningThresholdPercent());
                }
                return WriteCapDecision.allow();
            }

            if (result.startsWith("WRITE_CAP_EXCEEDED")) {
                if (result.contains(":ALERT")) {
                    alertLog.error("[RATE_LIMIT_ALERT] severity=ERROR type=WRITE_CAP_BREACH agent='{}' limit={}",
                            agentId, limit);
                }
                log.info("Hourly write cap reached for agent='{}' (limit={})", agentId, limit);
                return WriteCapDecision.exceed(EXCEEDED_MESSAGE);
            }

            log.warn("Unexpected result from write cap script: '{}'", result);
            return WriteCapDecision.unavailable(UNAVAILABLE_MESSAGE);

        } catch (Exception e) {
            log.error("Redis write cap limiter unavailable: {}", e.getMessage());
            return WriteCapDecision.unavailable(UNAVAILABLE_MESSAGE);
        }
    }

    private boolean isValidIdentifier(String s) {
        return s != null && !s.isBlank() && SAFE_KEY_PATTERN.matcher(s.trim()).matches();
    }
}
