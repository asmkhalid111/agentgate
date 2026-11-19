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

@DisplayName("Redis Rate Limiter & Daily Quota Atomic Lua Integration Tests (FR6)")
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
        properties.setDefaultAgentDailyQuota(100);
        properties.setDefaultToolDailyQuota(200);
        properties.setDefaultHourlyWriteCap(20);
        properties.setWarningThresholdPercent(80);

        cleanUpRedisKeys();

        rateLimiter = new RedisRateLimiter(redisTemplate, properties, clock);
    }

    @AfterEach
    void tearDown() {
        cleanUpRedisKeys();
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private void cleanUpRedisKeys() {
        try {
            Set<String> keys = redisTemplate.keys("agentgate:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    @DisplayName("1. Basic: Requests within RPM and daily limits are allowed")
    void requestsWithinLimit_areAllowed() {
        for (int i = 0; i < 5; i++) {
            RateLimitDecision decision = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
            assertTrue(decision.allowed(), "Request " + (i + 1) + " should be allowed");
            assertEquals(RateLimitDecision.DecisionType.ALLOWED, decision.type());
        }
    }

    @Test
    @DisplayName("2. RPM Limit: Per-agent RPM limit exceeded is rejected and does not increment daily counter")
    void agentRpmExceeded_isRejected_withAccurateDecision() {
        properties.setAgentLimits(Map.of(TEST_AGENT_1, 3));

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // 4th request exceeds agent RPM
        RateLimitDecision rejected = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertFalse(rejected.allowed());
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_EXCEEDED, rejected.type());
        assertTrue(rejected.message().contains("RATE_LIMIT_EXCEEDED"));

        // Verify daily counter stopped at 3 (zero partial increment)
        String dailyKey = "agentgate:quota:daily:agent:" + TEST_AGENT_1 + ":2026-11-19";
        String dailyVal = redisTemplate.opsForValue().get(dailyKey);
        assertEquals("3", dailyVal);
    }

    @Test
    @DisplayName("3. Daily Quota: Per-agent daily quota exceeded is rejected and does not increment RPM")
    void agentDailyQuotaExceeded_isRejected_andDoesNotIncrementRpm() {
        properties.setAgentDailyQuotas(Map.of(TEST_AGENT_1, 2));

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // 3rd request exceeds daily quota (even though RPM limit is 10)
        RateLimitDecision rejected = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertFalse(rejected.allowed());
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_EXCEEDED, rejected.type());
        assertTrue(rejected.message().contains("Daily request quota exceeded"));

        // Verify RPM counter remains at 2 (no partial increment)
        long minuteBucket = clock.millis() / 60000L;
        String rpmKey = "agentgate:ratelimit:agent:" + TEST_AGENT_1 + ":" + minuteBucket;
        assertEquals("2", redisTemplate.opsForValue().get(rpmKey));
    }

    @Test
    @DisplayName("4. Daily Quota: Per-tool daily quota exceeded blocks all agents for that tool")
    void toolDailyQuotaExceeded_isRejected_acrossAgents() {
        properties.setToolDailyQuotas(Map.of(TEST_TOOL_1, 2));

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());

        // 3rd request for TEST_TOOL_1 by TEST_AGENT_1 is rejected
        RateLimitDecision rejected = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertFalse(rejected.allowed());
        assertTrue(rejected.message().contains("Daily tool request quota exceeded"));

        // Different tool TEST_TOOL_2 remains accessible
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_2).allowed());
    }

    @Test
    @DisplayName("5. Isolation: Different agents have isolated RPM and daily quota counters")
    void differentAgents_haveIsolatedDailyAndRpmCounters() {
        properties.setAgentLimits(Map.of(TEST_AGENT_1, 2));
        properties.setAgentDailyQuotas(Map.of(TEST_AGENT_1, 2));

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // TEST_AGENT_2 has its own isolated counters
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_2, TEST_TOOL_1).allowed());
    }

    @Test
    @DisplayName("6. Rollover: Minute window rollover resets RPM while daily quota counter is preserved")
    void minuteWindowRollover_resetsRpm_whileDailyQuotaPreserved() {
        properties.setAgentLimits(Map.of(TEST_AGENT_1, 2));
        properties.setAgentDailyQuotas(Map.of(TEST_AGENT_1, 5));

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed(), "RPM exceeded in minute 1");

        // Advance clock by 61 seconds (next calendar minute, same day)
        clock.advance(Duration.ofSeconds(61));

        // RPM is reset, daily quota is preserved (now at 2/5)
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed(), "Allowed in new minute");
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed(), "RPM exceeded again");

        // Daily counter is at 4
        String dailyKey = "agentgate:quota:daily:agent:" + TEST_AGENT_1 + ":2026-11-19";
        assertEquals("4", redisTemplate.opsForValue().get(dailyKey));
    }

    @Test
    @DisplayName("7. Daily Reset: Midnight UTC rollover resets daily quota counters")
    void midnightUtcRollover_resetsDailyQuotaCounters() {
        properties.setAgentDailyQuotas(Map.of(TEST_AGENT_1, 2));

        // Start at 2026-11-19 23:59:00 UTC
        clock.setInstant(Instant.parse("2026-11-19T23:59:00Z"));

        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed(), "Daily quota reached on 2026-11-19");

        // Advance clock past midnight UTC to 2026-11-20 00:00:05 UTC
        clock.advance(Duration.ofSeconds(65));

        // In the new UTC day, daily quota is completely reset
        RateLimitDecision nextDayDecision = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertTrue(nextDayDecision.allowed(), "Daily quota must reset across midnight UTC");

        String newDailyKey = "agentgate:quota:daily:agent:" + TEST_AGENT_1 + ":2026-11-20";
        assertEquals("1", redisTemplate.opsForValue().get(newDailyKey));
    }

    @Test
    @DisplayName("8. Atomic Contention: 20 concurrent threads respect agent limit with zero over-allocation")
    void concurrentRequests_areAtomicUnderHighContention() throws Exception {
        int allowedLimit = 5;
        properties.setAgentLimits(Map.of(TEST_AGENT_1, allowedLimit));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        int allowedCount = 0;
        int rejectedCount = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
        }

        assertEquals(allowedLimit, allowedCount, "Exactly limit requests should be allowed");
        assertEquals(threadCount - allowedLimit, rejectedCount, "Remaining requests should be rejected");

        long minuteBucket = clock.millis() / 60000L;
        String agentKey = "agentgate:ratelimit:agent:" + TEST_AGENT_1 + ":" + minuteBucket;
        String val = redisTemplate.opsForValue().get(agentKey);
        assertEquals(String.valueOf(allowedLimit), val, "Redis counter must equal exact limit");
    }

    @Test
    @DisplayName("9. Alert Deduplication: Warning and breach alert keys are created with TTL and deduplicated")
    void warningAndBreachAlerts_areDeduplicatedInRedis() {
        // Daily quota = 5; 80% threshold = ceil(5 * 0.8) = 4
        properties.setAgentDailyQuotas(Map.of(TEST_AGENT_1, 5));
        properties.setWarningThresholdPercent(80);

        // Calls 1, 2, 3
        for (int i = 0; i < 3; i++) {
            assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        }

        String warnAlertKey = "agentgate:alert:quota:agent:" + TEST_AGENT_1 + ":2026-11-19:warn";
        String hardAlertKey = "agentgate:alert:quota:agent:" + TEST_AGENT_1 + ":2026-11-19:hard";
        assertNull(redisTemplate.opsForValue().get(warnAlertKey), "Warn alert not yet triggered at 3/5");

        // Call 4 hits 80% warning threshold (4/5)
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertEquals("1", redisTemplate.opsForValue().get(warnAlertKey), "Warn alert key must be set");
        Long warnTtl = redisTemplate.getExpire(warnAlertKey, TimeUnit.SECONDS);
        assertNotNull(warnTtl);
        assertTrue(warnTtl > 0 && warnTtl <= 172800, "Warn alert must have positive daily TTL");

        // Call 5 reaches 100% (5/5)
        assertTrue(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());

        // Call 6 is rejected (hard limit exceeded)
        RateLimitDecision rejected = rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);
        assertFalse(rejected.allowed());
        assertEquals("1", redisTemplate.opsForValue().get(hardAlertKey), "Hard breach alert key must be set");

        // Subsequent rejected calls do not overwrite or fail
        assertFalse(rateLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1).allowed());
        assertEquals("1", redisTemplate.opsForValue().get(hardAlertKey));
    }

    @Test
    @DisplayName("10. Outage: Redis unavailable strictly fails closed with safe generic error")
    void redisUnavailable_failsClosed_withSafeGenericError() {
        RedisStandaloneConfiguration badConfig = new RedisStandaloneConfiguration("127.0.0.1", 65530);
        LettuceConnectionFactory badFactory = new LettuceConnectionFactory(badConfig);
        badFactory.afterPropertiesSet();

        StringRedisTemplate badRedis = new StringRedisTemplate(badFactory);
        badRedis.afterPropertiesSet();

        RedisRateLimiter failingLimiter = new RedisRateLimiter(badRedis, properties, clock);
        RateLimitDecision decision = failingLimiter.tryAcquire(TEST_AGENT_1, TEST_TOOL_1);

        assertFalse(decision.allowed(), "Must fail closed on outage");
        assertEquals(RateLimitDecision.DecisionType.RATE_LIMIT_UNAVAILABLE, decision.type());
        assertEquals("RATE_LIMIT_UNAVAILABLE: Rate limiting is temporarily unavailable.", decision.message());

        badFactory.destroy();
    }

    @Test
    @DisplayName("11. Configuration: Startup validation rejects zero, negative, or invalid quotas")
    void startupValidation_rejectsZeroOrNegativeLimits() {
        RateLimitProperties invalidProps = new RateLimitProperties();
        invalidProps.setEnabled(true);
        invalidProps.setDefaultAgentDailyQuota(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class, invalidProps::validate);
        assertTrue(ex.getMessage().contains("default-agent-daily-quota must be positive"));

        invalidProps.setDefaultAgentDailyQuota(100);
        invalidProps.setDefaultHourlyWriteCap(25); // Exceeds max 20!
        IllegalStateException ex2 = assertThrows(IllegalStateException.class, invalidProps::validate);
        assertTrue(ex2.getMessage().contains("default-hourly-write-cap must be between 1 and 20"));

        invalidProps.setDefaultHourlyWriteCap(0); // Zero
        IllegalStateException ex3 = assertThrows(IllegalStateException.class, invalidProps::validate);
        assertTrue(ex3.getMessage().contains("default-hourly-write-cap must be between 1 and 20"));

        invalidProps.setDefaultHourlyWriteCap(20);
        invalidProps.setWarningThresholdPercent(100); // Invalid (must be 1..99)
        IllegalStateException ex4 = assertThrows(IllegalStateException.class, invalidProps::validate);
        assertTrue(ex4.getMessage().contains("warning-threshold-percent must be between 1 and 99"));
    }

    @Test
    @DisplayName("12. Sanitization: Malformed or injection identifiers fail closed safely")
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

        void setInstant(Instant instant) {
            this.instant = instant;
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
