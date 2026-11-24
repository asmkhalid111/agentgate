package com.agentgate.gateway.pipeline.benchmark;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Benchmark filter positioned immediately after Spring Security's AuthorizationFilter.
 * Records the high-resolution nanosecond timestamp when the security filter chain completes.
 */
public class BenchmarkSecurityExitFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (BenchmarkTimingContext.isTimingEnabled()) {
            BenchmarkTimingContext.setSecurityFilterExitNs(System.nanoTime());
        }
        filterChain.doFilter(request, response);
    }
}
