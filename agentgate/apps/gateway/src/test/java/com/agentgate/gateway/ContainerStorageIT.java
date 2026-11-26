package com.agentgate.gateway;

import com.agentgate.gateway.registry.repository.RegisteredToolRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.junit.jupiter.api.Assertions.*;

class ContainerStorageIT {
    @Test
    void migratedToolRegistryAndAtomicRedisCountersWork() {
        try (var postgres = new GenericContainer<>("postgres:16-alpine")
                     .withEnv("POSTGRES_PASSWORD", "fixture").withExposedPorts(5432)
                     .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));
             var redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)) {
            postgres.start();
            redis.start();
            var database = new DriverManagerDataSource("jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(5432) + "/postgres", "postgres", "fixture");
            Flyway.configure().dataSource(database).load().migrate();
            var tools = new RegisteredToolRepository(JdbcClient.create(database));
            assertTrue(tools.findByName("inventory_check").isPresent());

            var connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            try {
                connection.afterPropertiesSet();
                var counters = new StringRedisTemplate(connection);
                assertEquals(1L, counters.opsForValue().increment("integration:counter"));
                assertEquals(2L, counters.opsForValue().increment("integration:counter"));
            } finally {
                connection.destroy();
            }
        }
    }
}
