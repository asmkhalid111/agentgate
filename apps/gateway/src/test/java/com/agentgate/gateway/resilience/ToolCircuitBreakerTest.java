package com.agentgate.gateway.resilience;

import com.agentgate.gateway.resilience.circuitbreaker.CircuitBreakerState;
import com.agentgate.gateway.resilience.circuitbreaker.ToolCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolCircuitBreaker State Machine & Concurrency Tests")
class ToolCircuitBreakerTest {

    private MutableClock clock;
    private ToolCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-11-20T00:00:00Z"));
        breaker = new ToolCircuitBreaker("orders_refund", 3, Duration.ofSeconds(30), clock);
    }

    @Test
    @DisplayName("Initial state is CLOSED with zero failures and permits execution")
    void initialState_isClosed() {
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(0, breaker.getConsecutiveFailures());
        assertTrue(breaker.tryAcquirePermission());
    }

    @Test
    @DisplayName("Transitions CLOSED -> OPEN after exactly 3 consecutive downstream failures")
    void threeConsecutiveFailures_tripsBreakerToOpen() {
        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(1, breaker.getConsecutiveFailures());

        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(2, breaker.getConsecutiveFailures());

        assertTrue(breaker.tryAcquirePermission());
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());
        assertEquals(3, breaker.getConsecutiveFailures());
        assertNotNull(breaker.getOpenStateStartTime());
    }

    @Test
    @DisplayName("Success resets consecutive failure counter in CLOSED state")
    void success_resetsFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(2, breaker.getConsecutiveFailures());

        breaker.onSuccess();
        assertEquals(0, breaker.getConsecutiveFailures());
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());

        // Needs 3 fresh failures to open
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());
    }

    @Test
    @DisplayName("Fast-rejects requests while OPEN within wait duration")
    void openState_fastRejectsRequests() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        // Fast rejection
        assertFalse(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission());
        assertEquals(2, breaker.getRejectedRequests());

        // Advance 29 seconds (less than 30s openWaitDuration)
        clock.advance(Duration.ofSeconds(29));
        assertFalse(breaker.tryAcquirePermission());
        assertEquals(3, breaker.getRejectedRequests());
    }

    @Test
    @DisplayName("Transitions to HALF_OPEN after wait duration and grants exactly one trial permit")
    void waitDurationElapsed_transitionsToHalfOpen_withSingleTrialPermit() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());

        // Advance past openWaitDuration (30s)
        clock.advance(Duration.ofSeconds(30));

        // First caller acquires trial permit
        assertTrue(breaker.tryAcquirePermission());
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
        assertTrue(breaker.isHalfOpenTrialInFlight());

        // Concurrent/subsequent caller is rejected fast (no stampede)
        assertFalse(breaker.tryAcquirePermission());
        assertFalse(breaker.tryAcquirePermission());
        assertEquals(2, breaker.getRejectedRequests());
    }

    @Test
    @DisplayName("Successful trial in HALF_OPEN resets breaker back to CLOSED")
    void halfOpenSuccess_recoversToClosed() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquirePermission());

        // Trial succeeded
        breaker.onSuccess();
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
        assertEquals(0, breaker.getConsecutiveFailures());
        assertFalse(breaker.isHalfOpenTrialInFlight());

        // Normal requests proceed
        assertTrue(breaker.tryAcquirePermission());
    }

    @Test
    @DisplayName("Failed trial in HALF_OPEN immediately re-trips breaker back to OPEN with fresh timer")
    void halfOpenFailure_reOpensBreaker() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquirePermission());

        Instant trialFailureTime = clock.instant();
        // Trial failed downstream
        breaker.recordFailure();

        assertEquals(CircuitBreakerState.OPEN, breaker.getState());
        assertEquals(trialFailureTime, breaker.getOpenStateStartTime());
        assertFalse(breaker.isHalfOpenTrialInFlight());

        // Must wait another 30 seconds
        clock.advance(Duration.ofSeconds(15));
        assertFalse(breaker.tryAcquirePermission());

        clock.advance(Duration.ofSeconds(15));
        assertTrue(breaker.tryAcquirePermission());
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
    }

    @Test
    @DisplayName("Releasing permission in HALF_OPEN clears trial flag without opening or closing breaker")
    void releasePermission_clearsTrialFlagInHalfOpen() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        clock.advance(Duration.ofSeconds(30));
        assertTrue(breaker.tryAcquirePermission());
        assertTrue(breaker.isHalfOpenTrialInFlight());

        // Gateway stopped request before downstream dispatch (e.g. rate limit, kill switch)
        breaker.releasePermission();
        assertFalse(breaker.isHalfOpenTrialInFlight());
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());

        // Another request can now acquire trial permit
        assertTrue(breaker.tryAcquirePermission());
        assertTrue(breaker.isHalfOpenTrialInFlight());
    }

    @Test
    @DisplayName("Thread safety: only one thread acquires trial permit under concurrent load in HALF_OPEN")
    void concurrentHalfOpen_grantsExactlyOneTrialPermit() throws InterruptedException {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();

        clock.advance(Duration.ofSeconds(30));

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger grantedPermits = new AtomicInteger(0);
        AtomicInteger rejectedRequests = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (breaker.tryAcquirePermission()) {
                        grantedPermits.incrementAndGet();
                    } else {
                        rejectedRequests.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(1, grantedPermits.get(), "Exactly one trial permit should be granted");
        assertEquals(threadCount - 1, rejectedRequests.get(), "All other concurrent requests must be rejected");
    }

    private static class MutableClock extends Clock {
        private Instant current;
        private final ZoneId zone = ZoneId.of("UTC");

        public MutableClock(Instant initial) {
            this.current = initial;
        }

        public void advance(Duration duration) {
            this.current = this.current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
