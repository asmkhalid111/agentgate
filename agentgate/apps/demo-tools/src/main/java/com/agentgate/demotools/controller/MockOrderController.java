package com.agentgate.demotools.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/orders")
public class MockOrderController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MockOrderController.class);

    public record RefundRequest(@Positive Number amount) {}

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getOrder(@PathVariable("id") @jakarta.validation.constraints.NotBlank String id) {
        log.info("Handling demo order lookup");
        if (id != null && id.contains("fail")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Simulated downstream internal server error");
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
            @PathVariable("id") @jakarta.validation.constraints.NotBlank String id,
            @Valid @RequestBody(required = false) RefundRequest body) {
        if (id != null && id.contains("fail")) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Simulated downstream refund failure");
        }
        if (id != null && id.contains("delay")) {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException ignored) {
            }
        }
        Number amount = body != null && body.amount() != null ? body.amount() : 500.0;
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
