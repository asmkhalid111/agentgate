package com.agentgate.demotools.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class MockOrderController {

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable("id") String id) {
        return ResponseEntity.ok(Map.of(
                "orderId", id,
                "customerName", "Rahim Ahmed",
                "customerId", "cust-101",
                "amount", 1500.0,
                "currency", "BDT",
                "status", "DELIVERED",
                "timestamp", Instant.now().toString()
        ));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<Map<String, Object>> issueRefund(
            @PathVariable("id") String id,
            @RequestBody(required = false) Map<String, Object> body) {
        Object amount = body != null ? body.getOrDefault("amount", 500.0) : 500.0;
        return ResponseEntity.ok(Map.of(
                "refundId", "ref-" + System.currentTimeMillis(),
                "orderId", id,
                "refundAmount", amount,
                "currency", "BDT",
                "status", "REFUND_PROCESSED",
                "processedAt", Instant.now().toString()
        ));
    }
}

