package com.agentgate.gateway.pipeline.benchmark;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Benchmark filter that records high-resolution entry/exit nanosecond timestamps
 * and appends incremental gateway overhead headers when enabled.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "agentgate.benchmark.timing.enabled", havingValue = "true")
public class BenchmarkTimingFilter extends OncePerRequestFilter {

    static {
        BenchmarkTimingContext.setTimingEnabled(true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        long startNs = System.nanoTime();
        String correlationId = request.getHeader("X-Request-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        BenchmarkTimingContext.setGatewayStartNs(startNs);
        BenchmarkTimingContext.setCorrelationId(correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long totalNs = System.nanoTime() - startNs;
            Long downstreamNs = BenchmarkTimingContext.getDownstreamDurationNs();
            long downstream = (downstreamNs != null) ? downstreamNs : 0L;
            long overheadNs = Math.max(0, totalNs - downstream);

            if (!response.isCommitted()) {
                response.setHeader("X-Gateway-Duration-Ns", String.valueOf(totalNs));
                response.setHeader("X-Downstream-Duration-Ns", String.valueOf(downstream));
                response.setHeader("X-Gateway-Overhead-Ns", String.valueOf(overheadNs));
                response.setHeader("X-Request-Correlation-Id", correlationId);
            }
            BenchmarkTimingContext.clear();
        }
    }
}
