package com.agentgate.gateway.approval.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Asynchronous configuration for non-blocking webhook delivery (Reviewer Correction B).
 * Configures a dedicated bounded thread pool executor for outbound webhook notifications.
 */
@Configuration
@EnableAsync
public class WebhookAsyncConfig {

    @Bean(name = "webhookTaskExecutor")
    public Executor webhookTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("webhook-exec-");
        executor.initialize();
        return executor;
    }
}
