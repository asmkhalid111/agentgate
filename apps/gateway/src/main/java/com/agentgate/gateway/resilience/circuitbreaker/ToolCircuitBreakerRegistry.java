package com.agentgate.gateway.resilience.circuitbreaker;

import com.agentgate.gateway.resilience.config.ResilienceProperties;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ConcurrentHashMap<String, ToolCircuitBreaker> breakers = new ConcurrentHashMap<>();

    @Autowired
    public ToolCircuitBreakerRegistry(ResilienceProperties properties, @Autowired(required = false) Clock clock) {
        this.properties = properties;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public ToolCircuitBreakerRegistry(ResilienceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ToolCircuitBreaker getBreaker(String toolName) {
        return breakers.computeIfAbsent(toolName, name -> new ToolCircuitBreaker(
                name,
                properties.getFailureThreshold(name),
                properties.getOpenWaitDuration(name),
                clock
        ));
    }

    public Map<String, ToolCircuitBreaker> getAllBreakers() {
        return Collections.unmodifiableMap(breakers);
    }

    public void resetAll() {
        breakers.values().forEach(ToolCircuitBreaker::reset);
    }
}
