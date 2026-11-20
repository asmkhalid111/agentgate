package com.agentgate.gateway.resilience.circuitbreaker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Thread-safe per-tool circuit breaker protecting the gateway and downstream services (FR10).
 * <p>
 * State machine:
 * <ul>
 *   <li><b>CLOSED:</b> Normal operation. Increments consecutive downstream failures upon classified failure.
 *       Transitions to <b>OPEN</b> when consecutive failures reaches the configured threshold (default: 3).</li>
 *   <li><b>OPEN:</b> Rejects all requests immediately without downstream dispatch or write token consumption.
 *       Transitions to <b>HALF_OPEN</b> once the open wait duration (default: 30s) elapses.</li>
 *   <li><b>HALF_OPEN:</b> Allows exactly one probe/trial request to test downstream availability.
 *       Concurrent requests arriving while the probe is in-flight are rejected fast without stampeding.
 *       Transitions to <b>CLOSED</b> on probe success; transitions back to <b>OPEN</b> on probe failure.</li>
 * </ul>
 * <p>
 * Invariant: Never perform network I/O or downstream calls inside synchronized blocks.
 */
public class ToolCircuitBreaker {

    private final String toolName;
    private final int failureThreshold;
    private final Duration openWaitDuration;
    private final Clock clock;
    private final Consumer<CircuitBreakerStateChangedEvent> stateChangeCallback;

    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private int consecutiveFailures = 0;
    private Instant lastFailureTime;
    private Instant openStateStartTime;
    private boolean halfOpenTrialInFlight = false;
    private long successfulExecutions = 0;
    private long rejectedRequests = 0;

    public ToolCircuitBreaker(
            String toolName,
            int failureThreshold,
            Duration openWaitDuration,
            Clock clock,
            Consumer<CircuitBreakerStateChangedEvent> stateChangeCallback) {
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.failureThreshold = failureThreshold > 0 ? failureThreshold : 3;
        this.openWaitDuration = Objects.requireNonNull(openWaitDuration, "openWaitDuration must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.stateChangeCallback = stateChangeCallback;
    }

    public ToolCircuitBreaker(String toolName, int failureThreshold, Duration openWaitDuration, Clock clock) {
        this(toolName, failureThreshold, openWaitDuration, clock, null);
    }

    public ToolCircuitBreaker(String toolName, int failureThreshold, Duration openWaitDuration) {
        this(toolName, failureThreshold, openWaitDuration, Clock.systemUTC(), null);
    }

    /**
     * Checks whether execution is permitted for the tool.
     * In CLOSED state: returns true.
     * In OPEN state: checks if openWaitDuration has passed. If so, transitions to HALF_OPEN,
     * marks a single trial request in-flight, and returns true. Otherwise rejects fast (returns false).
     * In HALF_OPEN state: permits at most one in-flight trial request; rejects concurrent requests fast.
     */
    public boolean tryAcquirePermission() {
        CircuitBreakerStateChangedEvent eventToPublish = null;
        boolean permitted = false;

        synchronized (this) {
            Instant now = clock.instant();
            switch (state) {
                case CLOSED -> {
                    permitted = true;
                }
                case OPEN -> {
                    if (openStateStartTime != null && !now.isBefore(openStateStartTime.plus(openWaitDuration))) {
                        state = CircuitBreakerState.HALF_OPEN;
                        halfOpenTrialInFlight = true;
                        permitted = true;
                        eventToPublish = new CircuitBreakerStateChangedEvent(toolName, CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN);
                    } else {
                        rejectedRequests++;
                        permitted = false;
                    }
                }
                case HALF_OPEN -> {
                    if (!halfOpenTrialInFlight) {
                        halfOpenTrialInFlight = true;
                        permitted = true;
                    } else {
                        rejectedRequests++;
                        permitted = false;
                    }
                }
            }
        }

        if (eventToPublish != null && stateChangeCallback != null) {
            try {
                stateChangeCallback.accept(eventToPublish);
            } catch (Exception ignored) {
            }
        }

        return permitted;
    }

    /**
     * Records a successful execution.
     * In HALF_OPEN: transitions to CLOSED, resets consecutive failures and clears trial in-flight flag.
     * In CLOSED: resets consecutive failures to 0.
     */
    public void onSuccess() {
        CircuitBreakerStateChangedEvent eventToPublish = null;

        synchronized (this) {
            successfulExecutions++;
            consecutiveFailures = 0;
            if (state == CircuitBreakerState.HALF_OPEN) {
                state = CircuitBreakerState.CLOSED;
                halfOpenTrialInFlight = false;
                openStateStartTime = null;
                eventToPublish = new CircuitBreakerStateChangedEvent(toolName, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED);
            }
        }

        if (eventToPublish != null && stateChangeCallback != null) {
            try {
                stateChangeCallback.accept(eventToPublish);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Records a classified downstream tool failure.
     * In HALF_OPEN: transitions immediately back to OPEN, resets open wait timer, and clears trial in-flight flag.
     * In CLOSED: increments consecutive failures. If threshold reached, transitions to OPEN.
     */
    public void recordFailure() {
        CircuitBreakerStateChangedEvent eventToPublish = null;

        synchronized (this) {
            Instant now = clock.instant();
            lastFailureTime = now;
            if (state == CircuitBreakerState.HALF_OPEN) {
                state = CircuitBreakerState.OPEN;
                openStateStartTime = now;
                halfOpenTrialInFlight = false;
                eventToPublish = new CircuitBreakerStateChangedEvent(toolName, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN);
            } else if (state == CircuitBreakerState.CLOSED) {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    state = CircuitBreakerState.OPEN;
                    openStateStartTime = now;
                    eventToPublish = new CircuitBreakerStateChangedEvent(toolName, CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN);
                }
            }
        }

        if (eventToPublish != null && stateChangeCallback != null) {
            try {
                stateChangeCallback.accept(eventToPublish);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Releases a previously acquired trial permission if governance checks (e.g. rate limit,
     * kill switch, schema validation) blocked execution before actual downstream dispatch.
     * Ensures HALF_OPEN is not permanently stuck due to gateway-side rejections.
     */
    public synchronized void releasePermission() {
        if (state == CircuitBreakerState.HALF_OPEN && halfOpenTrialInFlight) {
            halfOpenTrialInFlight = false;
        }
    }

    /**
     * Read-only availability inspection for MCP discovery (FR10).
     * Does NOT acquire a HALF_OPEN trial permit, does NOT change state, and does NOT execute any downstream call.
     *
     * @return true if tool is CLOSED, or if OPEN wait duration has elapsed (eligible for HALF_OPEN trial),
     *         or if currently in HALF_OPEN; false if OPEN and still in cooldown.
     */
    public synchronized boolean isAvailableForDiscovery() {
        if (state == CircuitBreakerState.CLOSED) {
            return true;
        }
        Instant now = clock.instant();
        if (state == CircuitBreakerState.OPEN) {
            if (openStateStartTime != null && !now.isBefore(openStateStartTime.plus(openWaitDuration))) {
                // Cooldown elapsed: eligible for controlled trial
                return true;
            }
            // Still in cooldown
            return false;
        }
        // HALF_OPEN: discoverable so authorized caller can attempt trial
        return true;
    }

    /**
     * Returns the current state, dynamically reflecting whether OPEN duration has elapsed.
     */
    public synchronized CircuitBreakerState getState() {
        Instant now = clock.instant();
        if (state == CircuitBreakerState.OPEN && openStateStartTime != null) {
            if (!now.isBefore(openStateStartTime.plus(openWaitDuration))) {
                return CircuitBreakerState.HALF_OPEN;
            }
        }
        return state;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized Instant getLastFailureTime() {
        return lastFailureTime;
    }

    public synchronized Instant getOpenStateStartTime() {
        return openStateStartTime;
    }

    public synchronized boolean isHalfOpenTrialInFlight() {
        return halfOpenTrialInFlight;
    }

    public synchronized long getSuccessfulExecutions() {
        return successfulExecutions;
    }

    public synchronized long getRejectedRequests() {
        return rejectedRequests;
    }

    public synchronized void reset() {
        state = CircuitBreakerState.CLOSED;
        consecutiveFailures = 0;
        lastFailureTime = null;
        openStateStartTime = null;
        halfOpenTrialInFlight = false;
    }

    public String getToolName() {
        return toolName;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public Duration getOpenWaitDuration() {
        return openWaitDuration;
    }
}
