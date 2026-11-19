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

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Redis Rolling Hourly Write Cap Integration Tests (FR6)")
class RedisWriteCapLimiterIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private MutableClock clock;
    private RateLimitProperties properties;
    private RedisWriteCapLimiter writeCapLimiter;

    private static final String TEST_AGENT_1 = "test-agent-alpha";
    private static final String TEST_AGENT_2 = "test-agent-beta";

    @BeforeEach
    void setUp() {
        String redisHost = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int redisPort = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        connectionFactory = new LettuceConnectionFactory(config);
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));

        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setDefaultHourlyWriteCap(20);
        properties.setWarningThresholdPercent(80);

        cleanUpRedisKeys();

        writeCapLimiter = new RedisWriteCapLimiter(redisTemplate, properties, clock);
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
    @DisplayName("1. Write Cap: Exactly 20 writes permitted, the 21st is rejected")
    void exactly20WritesPermitted_21stRejected() {
        for (int i = 0; i < 20; i++) {
            WriteCapDecision decision = writeCapLimiter.tryAcquireWrite(TEST_AGENT_1);
            assertTrue(decision.allowed(), "Write dispatch " + (i + 1) + " must be allowed");
            assertEquals(WriteCapDecision.DecisionType.ALLOWED, decision.type());
        }

        // 21st write dispatch must be rejected
        WriteCapDecision rejected = writeCapLimiter.tryAcquireWrite(TEST_AGENT_1);
        assertFalse(rejected.allowed(), "21st write dispatch must be rejected");
        assertEquals(WriteCapDecision.DecisionType.WRITE_CAP_EXCEEDED, rejected.type());
        assertTrue(rejected.message().contains("Hourly write dispatch cap reached"));

        // Verify sorted set size in Redis is exactly 20
        String zsetKey = "agentgate:writecap:agent:" + TEST_AGENT_1;
        Long count = redisTemplate.opsForZSet().zCard(zsetKey);
        assertEquals(20L, count);
    }

    @Test
    @DisplayName("2. Rolling Window Boundary: 20 writes at 10:59 cannot be followed by another 20 at 11:01")
    void rollingWindowBoundary_enforcesStrict3600SecondWindow() {
        // Position clock at 10:59:00 UTC
        clock.setInstant(Instant.parse("2026-11-19T10:59:00Z"));

        // Agent consumes 20 writes at 10:59
        for (int i = 0; i < 20; i++) {
            assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
        }

        // Advance clock by 2 minutes to 11:01:00 UTC (crosses the hour mark!)
        clock.advance(Duration.ofMinutes(2));

        // In a fixed-hour bucket, 11:01 would reset the limit.
        // But with our mandatory ROLLING ZSET window, 10:59 is still within the 3,600s window!
        WriteCapDecision boundaryDecision = writeCapLimiter.tryAcquireWrite(TEST_AGENT_1);
        assertFalse(boundaryDecision.allowed(), "Must be blocked at 11:01 because 20 writes were performed within rolling 3600s");
        assertEquals(WriteCapDecision.DecisionType.WRITE_CAP_EXCEEDED, boundaryDecision.type());

        // Now advance clock past 11:59:00 UTC (e.g. to 11:59:01 UTC - 3601 seconds after 10:59:00)
        clock.setInstant(Instant.parse("2026-11-19T11:59:01Z"));

        // Now the entries from 10:59 have rolled out of the 3600-second window
        WriteCapDecision nextHourDecision = writeCapLimiter.tryAcquireWrite(TEST_AGENT_1);
        assertTrue(nextHourDecision.allowed(), "Writes must be permitted after rolling 3600s window passes");
    }

    @Test
    @DisplayName("3. Concurrency: 25 concurrent threads never exceed the cap of 20")
    void concurrentWriteAttempts_neverExceedCap() throws Exception {
        int cap = 20;
        int threadCount = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            tasks.add(() -> writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
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

        assertEquals(cap, allowedCount, "Exactly 20 concurrent attempts should be permitted");
        assertEquals(threadCount - cap, rejectedCount, "5 attempts should be rejected");

        String zsetKey = "agentgate:writecap:agent:" + TEST_AGENT_1;
        Long count = redisTemplate.opsForZSet().zCard(zsetKey);
        assertEquals(20L, count, "ZSET size must equal exactly 20");
    }

    @Test
    @DisplayName("4. Custom Cap: Lower configured write cap (e.g. 5) works correctly")
    void lowerConfiguredCap_worksCorrectly() {
        properties.setAgentHourlyWriteCaps(Map.of(TEST_AGENT_1, 5));

        for (int i = 0; i < 5; i++) {
            assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
        }

        WriteCapDecision rejected = writeCapLimiter.tryAcquireWrite(TEST_AGENT_1);
        assertFalse(rejected.allowed());
        assertEquals(WriteCapDecision.DecisionType.WRITE_CAP_EXCEEDED, rejected.type());

        // Other agent TEST_AGENT_2 still has default cap (20)
        assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_2).allowed());
    }

    @Test
    @DisplayName("5. Alert Deduplication: 80% warning and hard limit alerts set Redis keys and deduplicate")
    void alertKeys_areCreatedAndDeduplicated() {
        properties.setDefaultHourlyWriteCap(10);
        properties.setWarningThresholdPercent(80); // 80% of 10 = 8

        String warnAlertKey = "agentgate:alert:writecap:agent:" + TEST_AGENT_1 + ":warn";
        String hardAlertKey = "agentgate:alert:writecap:agent:" + TEST_AGENT_1 + ":hard";

        // Dispatches 1..7 (under 80%)
        for (int i = 0; i < 7; i++) {
            assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
        }
        assertNull(redisTemplate.opsForValue().get(warnAlertKey));

        // Dispatch 8 hits 80% warning threshold (8/10)
        assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
        assertEquals("1", redisTemplate.opsForValue().get(warnAlertKey), "Warn alert key must be set");

        // Dispatches 9, 10
        assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
        assertTrue(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());

        // Dispatch 11 hits hard cap (100% breach)
        WriteCapDecision breach = writeCapLimiter.tryAcquireWrite(TEST_AGENT_1);
        assertFalse(breach.allowed());
        assertEquals("1", redisTemplate.opsForValue().get(hardAlertKey), "Hard breach alert key must be set");

        // Subsequent rejected calls do not disrupt state or fail
        assertFalse(writeCapLimiter.tryAcquireWrite(TEST_AGENT_1).allowed());
        assertEquals("1", redisTemplate.opsForValue().get(hardAlertKey));
    }

    @Test
    @DisplayName("6. Outage: Redis unavailable strictly fails closed")
    void redisUnavailable_failsClosed() {
        RedisStandaloneConfiguration badConfig = new RedisStandaloneConfiguration("127.0.0.1", 65530);
        LettuceConnectionFactory badFactory = new LettuceConnectionFactory(badConfig);
        badFactory.afterPropertiesSet();

        StringRedisTemplate badRedis = new StringRedisTemplate(badFactory);
        badRedis.afterPropertiesSet();

        RedisWriteCapLimiter failingLimiter = new RedisWriteCapLimiter(badRedis, properties, clock);
        WriteCapDecision decision = failingLimiter.tryAcquireWrite(TEST_AGENT_1);

        assertFalse(decision.allowed(), "Must fail closed on outage");
        assertEquals(WriteCapDecision.DecisionType.WRITE_CAP_UNAVAILABLE, decision.type());
        assertEquals("RATE_LIMIT_UNAVAILABLE: Rate limiting is temporarily unavailable.", decision.message());

        badFactory.destroy();
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
