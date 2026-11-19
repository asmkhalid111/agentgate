package com.agentgate.gateway.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Redis Rate Limiter Atomic Lua Integration Tests (FR6)")
class RedisRateLimiterIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private MutableClock clock;
    private RateLimitProperties properties;
    private RedisRateLimiter rateLimiter;

    private static final String TEST_AGENT_1 = "test-agent-alpha";
    private static final String TEST_AGENT_2 = "test-agent-beta";
    private static final String TEST_TOOL_1 = "test_tool_orders";
    private static final String TEST_TOOL_2 = "test_tool_payments";

    @BeforeEach
    void setUp() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        clock = new MutableClock(Instant.parse("2026-11-19T12:00:00Z"));

        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setDefaultAgentRpm(10);
        properties.setDefaultToolRpm(20);

        rateLimiter = new RedisRateLimiter(redisTemplate, properties, clock);
        cleanupTestKeys();
    }

    @AfterEach
    void tearDown() {
        cleanupTestKeys();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private void cleanupTestKeys() {
        try {
            Set<String> keys = redisTemplate.keys("agentgate:ratelimit:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("1. Requests within configured limits are allowed")
    void requestsWithinConfiguredLimits_areAllowed() {
        properties.setDefaultAgentRpm(5);
        properties.setDefaultToolRpm(5);

        for (int i = 1; i <= 5; i++) {
            RateLimitDecision decision = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
            assertTrue(decision.allowed(), "Request " + i + " should be allowed");
            assertEquals(RateLimitDecision.DecisionType.ALLOWED, decision.type());
            assertNull(decision.message());
        }
    }

    @Test
    @DisplayName("2. Per-agent limit exceeded blocks subsequent calls with safe error")
    void perAgentLimitExceeded_blocksWithSafeMessage() {
        properties.setDefaultAgentRpm(3);
        properties.setDefaultToolRpm(100);

        // First 3 calls succeed
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        }

        // 4th call exceeds per-agent limit
        RateLimitDecision blocked = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertFalse(blocked.allowed());
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_EXCEEDED, blocked.type());
        assertEquals("RATE_LIMIT_EXCEEDED: Agent or tool request limit exceeded. Retry after the current window resets.",
                blocked.message());
    }

    @Test
    @DisplayName("3. Per-tool limit exceeded blocks subsequent calls across all agents")
    void perToolLimitExceeded_blocksAcrossAgents() {
        properties.setDefaultAgentRpm(100);
        properties.setDefaultToolRpm(2);

        // 2 calls to TEST_TOOL_1 from different agents
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());

        // 3rd call to TEST_TOOL_1 must be blocked, even from a fresh agent
        RateLimitDecision blocked = rateLimiter.tryAcquire("agent-third", TEST_TOOL_1);
        assertFalse(blocked.allowed());
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_EXCEEDED, blocked.type());
    }

    @Test
    @DisplayName("4. Independent counters for distinct agents")
    void independentCounters_forDistinctAgents() {
        properties.setDefaultAgentRpm(2);
        properties.setDefaultToolRpm(100);

        // Saturate TEST_AGENT_1
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // TEST_AGENT_2 must still have full quota on the same tool
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());
    }

    @Test
    @DisplayName("5. Independent counters for distinct tools")
    void independentCounters_forDistinctTools() {
        properties.setDefaultAgentRpm(100);
        properties.setDefaultToolRpm(2);

        // Saturate TEST_TOOL_1
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // TEST_TOOL_2 must still have full capacity
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_2).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_2).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_2).allowed());
    }

    @Test
    @DisplayName("6. Minute-window rollover and key TTL expiration")
    void minuteWindowRollover_resetsCapacityAndSetsTtl() {
        properties.setDefaultAgentRpm(2);
        properties.setDefaultToolRpm(2);

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // Verify TTL is positive on created keys
        long bucket = clock.millis() / 60_000L;
        String agentKey = "agentgate:ratelimit:agent:" + TEST_AGENT_1 + ":" + bucket;
        Long ttl = redisTemplate.getExpire(agentKey, TimeUnit.SECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 120, "TTL should be set to window + grace buffer (<= 120s)");

        // Advance clock by 61 seconds into next calendar-minute window
        clock.advance(Duration.ofSeconds(61));

        // Capacity is immediately available in new window
        RateLimitDecision nextWindowDecision = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertTrue(nextWindowDecision.allowed(), "Requests should be allowed in the next minute window");
    }

    @Test
    @DisplayName("7. Atomic concurrency: high-concurrency race does not exceed limit")
    void atomicConcurrency_doesNotExceedLimit() throws Exception {
        int limit = 5;
        properties.setDefaultAgentRpm(limit);
        properties.setDefaultToolRpm(100);

        int totalThreads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        List<Callable<RateLimitDecision>> tasks = new ArrayList<>();

        for (int i = 0; i < totalThreads; i++) {
            tasks.add(() -> rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1));
        }

        List<Future<RateLimitDecision>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        int allowedCount = 0;
        int rejectedCount = 0;

        for (Future<RateLimitDecision> future : futures) {
            RateLimitDecision decision = future.get();
            if (decision.allowed()) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }

        assertEquals(limit, allowedCount, "Exactly " + limit + " requests must be allowed under concurrent load");
        assertEquals(totalThreads - limit, rejectedCount, "Remaining concurrent requests must be rejected");

        // Verify Redis counter value matches limit exactly
        long bucket = clock.millis() / 60_000L;
        String agentKey = "agentgate:ratelimit:agent:" + TEST_AGENT_1 + ":" + bucket;
        String val = redisTemplate.opsForValue().get(agentKey);
        assertEquals(String.valueOf(limit), val, "Redis counter must not exceed the configured limit");
    }

    @Test
    @DisplayName("8. Redis unavailable — strict fail closed with safe generic error")
    void redisUnavailable_failsClosedSafely() {
        // Create rate limiter with destroyed connection factory
        LettuceConnectionFactory brokenFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 59999)); // Non-existent port
        brokenFactory.afterPropertiesSet();

        StringRedisTemplate brokenRedis = new StringRedisTemplate(brokenFactory);
        brokenRedis.afterPropertiesSet();

        RedisRateLimiter brokenLimiter = new RedisRateLimiter(brokenRedis, properties, clock);

        RateLimitDecision decision = brokenLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertFalse(decision.allowed(), "Must fail closed when Redis is unavailable");
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_UNAVAILABLE, decision.type());
        assertEquals("RATE_LIMIT_UNAVAILABLE: Rate limiting is temporarily unavailable.", decision.message());

        // Ensure no internal connection string or stack trace leaked
        assertFalse(decision.message().contains("59999"));
        assertFalse(decision.message().contains("Connection refused"));
        assertFalse(decision.message().contains("redis"));

        brokenFactory.destroy();
    }

    @Test
    @DisplayName("9. Configuration: Startup validation rejects zero or negative limits")
    void startupValidation_rejectsZeroOrNegativeLimits() {
        RateLimitProperties invalidProps = new RateLimitProperties();
        invalidProps.setEnabled(true);
        invalidProps.setDefaultAgentRpm(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class, invalidProps::validate);
        assertTrue(ex.getMessage().contains("default-agent-rpm must be positive"));

        invalidProps.setDefaultAgentRpm(10);
        invalidProps.setAgentLimits(Map.of("bad-agent", -5));
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, invalidProps::validate);
        assertTrue(ex2.getMessage().contains("must be positive"));
    }

    @Test
    @DisplayName("10. Sanitization: Malformed or injection identifiers fail closed safely")
    void malformedIdentifiers_failClosedSafely() {
        RateLimitDecision d1 = rateLimiter.tryAcquire("agent with spaces\r\nSET bad 1", TEST_TOOL_1);
        assertFalse(d1.allowed());
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_UNAVAILABLE, d1.type());

        RateLimitDecision d2 = rateLimiter.tryAcquire(null, TEST_TOOL_1);
        assertFalse(d2.allowed());

        RateLimitDecision d3 = rateLimiter.tryAcquire(TEST_AGENT_1, "");
        assertFalse(d3.allowed());
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant) {
            this.instant = instant;
            this.zone = ZoneOffset.UTC;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
