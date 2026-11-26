package com.agentgate.demotools.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockOrderUnitTest {
    @Test
    void refundUsesRequestedAmount() {
        var response = new MockOrderController().issueRefund("demo", java.util.Map.of("amount", 120));
        assertEquals(120, response.getBody().get("refundAmount"));
    }
}
