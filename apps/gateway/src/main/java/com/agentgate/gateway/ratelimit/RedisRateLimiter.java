package com.agentgate.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Redis-backed atomic rate limiter enforcing per-agent and per-tool requests-per-minute limits (FR6).
 * <p>
 * Architecture & Safety Invariants:
 * <ul>
 *   <li><b>Fixed-Window Limiter:</b> Enforces requests-per-minute against fixed 60-second calendar-minute windows
 *       (bucket = epoch_ms / 60000). Note: This is an atomic fixed-window limiter, not a sliding-window limiter.</li>
 *   <li><b>Dual-Dimension Atomic Gate:</b> Checks both per-agent and per-tool counters in a single Redis Lua script.
 *       If either counter meets or exceeds its limit, neither counter is incremented.</li>
 *   <li><b>Fail-Closed on Outage:</b> If Redis is unreachable, times out, or errors, requests fail closed with a safe
 *       generic message to prevent unmetered governance bypass.</li>
 *   <li><b>Privacy:</b> Never leaks Redis host addresses, credentials, stack traces, or internal keys to clients.</li>
 * </ul>
 */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String EXCEEDED_MESSAGE =
            "RATE_LIMIT_EXCEEDED: Agent or tool request limit exceeded. Retry after the current window resets.";
    private static final String UNAVAILABLE_MESSAGE =
            "RATE_LIMIT_UNAVAILABLE: Rate limiting is temporarily unavailable.";

    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-\\.]+$");
    private static final long WINDOW_MS = 60_000L;
    private static final long KEY_TTL_SECONDS = 120L;

    private static final String ATOMIC_LUA_SCRIPT = """
            local agent_limit = tonumber(ARGV[1])
            local tool_limit = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])

            local agent_val = redis.call('GET', KEYS[1])
            local agent_count = agent_val and tonumber(agent_val) or 0

            local tool_val = redis.call('GET', KEYS[2])
            local tool_count = tool_val and tonumber(tool_val) or 0

            if agent_count >= agent_limit then
                return 'AGENT_EXCEEDED'
            end

            if tool_count >= tool_limit then
                return 'TOOL_EXCEEDED'
            end

            local new_agent = redis.call('INCR', KEYS[1])
            if new_agent == 1 then
                redis.call('EXPIRE', KEYS[1], ttl)
            end

            local new_tool = redis.call('INCR', KEYS[2])
            if new_tool == 1 then
                redis.call('EXPIRE', KEYS[2], ttl)
            end

            return 'OK'
            """;

    private final StringRedisTemplate redisTemplate;
    private final RateLimitProperties properties;
    private final DefaultRedisScript<String> redisScript;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
        this(redisTemplate, properties, Clock.systemUTC());
    }

    public RedisRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.redisScript = new DefaultRedisScript<>(ATOMIC_LUA_SCRIPT, String.class);
    }

    @Override
    public RateLimitDecision tryAcquire(String agentId, String toolName) {
        if (!properties.isEnabled()) {
            return RateLimitDecision.allow();
        }

        // Validate identifiers to prevent Redis key injection or malformed keys
        if (!isValidIdentifier(agentId) || !isValidIdentifier(toolName)) {
            log.warn("Rate limit rejected due to invalid identifier: agentId='{}', toolName='{}'", agentId, toolName);
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
        }

        int agentLimit = properties.getAgentRpm(agentId);
        int toolLimit = properties.getToolRpm(toolName);

        if (agentLimit <= 0 || toolLimit <= 0) {
            log.error("Invalid rate limits configured for agent='{}' ({}) or tool='{}' ({})",
                    agentId, agentLimit, toolName, toolLimit);
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
        }

        long minuteBucket = clock.millis() / WINDOW_MS;
        String agentKey = "agentgate:ratelimit:agent:" + agentId + ":" + minuteBucket;
        String toolKey = "agentgate:ratelimit:tool:" + toolName + ":" + minuteBucket;

        try {
            String result = redisTemplate.execute(
                    redisScript,
                    List.of(agentKey, toolKey),
                    String.valueOf(agentLimit),
                    String.valueOf(toolLimit),
                    String.valueOf(KEY_TTL_SECONDS)
            );

            if ("OK".equals(result)) {
                return RateLimitDecision.allow();
            }

            log.info("Rate limit exceeded [decision={}]: agent='{}' (limit={}), tool='{}' (limit={})",
                    result, agentId, agentLimit, toolName, toolLimit);
            return RateLimitDecision.exceed(EXCEEDED_MESSAGE);

        } catch (Exception e) {
            // Strict fail-closed on Redis outage / connection error
            log.error("Redis rate limiter unavailable: {}", e.getMessage());
            return RateLimitDecision.unavailable(UNAVAILABLE_MESSAGE);
        }
    }

    private boolean isValidIdentifier(String s) {
        return s != null && !s.isBlank() && SAFE_KEY_PATTERN.matcher(s.trim()).matches();
    }
}
