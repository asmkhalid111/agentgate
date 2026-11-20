package com.agentgate.gateway.resilience.circuitbreaker;

import com.agentgate.gateway.resilience.config.ResilienceProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry managing per-tool circuit breakers (FR10).
 */
@Component
public class ToolCircuitBreakerRegistry {

    private final ResilienceProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentHashMap<String, ToolCircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    public ToolCircuitBreakerRegistry(
            ResilienceProperties properties,
            @Autowired(required = false) Clock clock,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.eventPublisher = eventPublisher;
    }

    public ToolCircuitBreakerRegistry(ResilienceProperties properties, Clock clock) {
        this(properties, clock, null);
    }

    public ToolCircuitBreakerRegistry(ResilienceProperties properties) {
        this(properties, Clock.systemUTC(), null);
    }

    public ToolCircuitBreaker getBreaker(String toolName) {
        return breakers.computeIfAbsent(toolName, name -> new ToolCircuitBreaker(
                name,
                properties.getFailureThreshold(name),
                properties.getOpenWaitDuration(name),
                clock,
                eventPublisher != null ? eventPublisher::publishEvent : null
        ));
    }

    public Map<String, ToolCircuitBreaker> getAllBreakers() {
        return Collections.unmodifiableMap(breakers);
    }

    public void resetAll() {
        breakers.values().forEach(ToolCircuitBreaker::reset);
    }
}
