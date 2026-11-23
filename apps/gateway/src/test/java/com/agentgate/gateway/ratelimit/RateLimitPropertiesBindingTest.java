package com.agentgate.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class TestConfig {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldBindFromBenchmarkProfileProperties() {
        runner.withPropertyValues(
                "agentgate.ratelimit.default-agent-rpm=12000",
                "agentgate.ratelimit.default-tool-rpm=12000",
                "agentgate.ratelimit.agent-limits.agent-support-bot=12000",
                "agentgate.ratelimit.tool-limits.benchmark_read=12000",
                "agentgate.ratelimit.agent-daily-quotas.agent-support-bot=1000000",
                "agentgate.ratelimit.tool-daily-quotas.benchmark_read=1000000"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties props = context.getBean(RateLimitProperties.class);
            assertThat(props.getAgentRpm("agent-support-bot")).isEqualTo(12000);
            assertThat(props.getToolRpm("benchmark_read")).isEqualTo(12000);
            assertThat(props.getAgentDailyQuota("agent-support-bot")).isEqualTo(1000000);
            assertThat(props.getToolDailyQuota("benchmark_read")).isEqualTo(1000000);
        });
    }

    @Test
    void shouldBindMapKeysWithBracketNotation() {
        runner.withPropertyValues(
                "agentgate.ratelimit.agent-limits[agent-support-bot]=12000",
                "agentgate.ratelimit.tool-limits[benchmark_read]=12000",
                "agentgate.ratelimit.agent-daily-quotas[agent-support-bot]=1000000",
                "agentgate.ratelimit.tool-daily-quotas[benchmark_read]=1000000"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            RateLimitProperties props = context.getBean(RateLimitProperties.class);
            assertThat(props.getAgentRpm("agent-support-bot")).isEqualTo(12000);
            assertThat(props.getToolRpm("benchmark_read")).isEqualTo(12000);
            assertThat(props.getAgentDailyQuota("agent-support-bot")).isEqualTo(1000000);
            assertThat(props.getToolDailyQuota("benchmark_read")).isEqualTo(1000000);
        });
    }
}
