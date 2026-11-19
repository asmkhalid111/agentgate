package com.agentgate.gateway.pipeline;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated bounded thread pool executor for asynchronous approved tool execution (FR4).
 * Keeps human approval HTTP requests completely unblocked from downstream tool latency.
 */
@Configuration
@EnableAsync
public class ApprovedExecutionAsyncConfig {

    @Bean(name = "approvedToolExecutionExecutor")
    public Executor approvedToolExecutionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("approved-exec-");
        executor.initialize();
        return executor;
    }
}
