package com.agentgate.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // Verifies that the Spring Boot 4 application context boots successfully with test profile
    }
}

