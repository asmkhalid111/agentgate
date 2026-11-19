package com.agentgate.demotools.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal webhook receiver endpoint in demo-tools for local notification demonstration (FR4).
 * Acknowledges receipt and logs only safe metadata (approval ID, tool name, status).
 * Avoids logging raw request bodies or user/argument data.
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    @PostMapping("/approvals")
    public ResponseEntity<Map<String, Object>> receiveApprovalWebhook(@RequestBody Map<String, Object> payload) {
        String approvalId = payload != null ? String.valueOf(payload.get("approvalId")) : "unknown";
        String toolName = payload != null ? String.valueOf(payload.get("toolName")) : "unknown";
        String status = payload != null ? String.valueOf(payload.get("status")) : "unknown";

        log.info("DemoTools: Received approval webhook notification: approvalId='{}', toolName='{}', status='{}'",
                approvalId, toolName, status);

        return ResponseEntity.ok(Map.of(
                "status", "ACKNOWLEDGED",
                "approvalId", approvalId
        ));
    }
}
