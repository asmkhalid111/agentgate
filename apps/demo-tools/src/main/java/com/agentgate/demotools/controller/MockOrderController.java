package com.agentgate.demotools.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class MockOrderController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MockOrderController.class);

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable("id") String id) {
        log.info("Handling demo order lookup");
        if (id != null && id.contains("fail")) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Simulated downstream internal server error",
                    "orderId", id
            ));
        }
        if (id != null && id.contains("delay")) {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
        }
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
        if (id != null && id.contains("fail")) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Simulated downstream refund failure",
                    "orderId", id
            ));
        }
        if (id != null && id.contains("delay")) {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
        }
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
