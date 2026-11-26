package com.agentgate.gateway.common;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoServiceCredentialTest {
    @Test
    void attachesCredentialOnlyToItsConfiguredOrigin() {
        var credential = new DemoServiceCredential("http://demo-tools:8082", "test-fixture");
        var headers = new HashMap<String, String>();
        credential.apply(URI.create("http://demo-tools:8082/api/orders/demo"), headers::put);
        assertEquals("test-fixture", headers.remove("X-AgentGate-Demo-Token"));
        for (String target : new String[]{"http://another:8082/mcp", "http://demo-tools:8083/mcp",
                "https://demo-tools:8082/mcp", "http://user@demo-tools:8082/mcp"}) {
            credential.apply(URI.create(target), headers::put);
            assertTrue(headers.isEmpty(), target);
        }
    }
}
