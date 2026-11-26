package com.agentgate.demotools.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Minimal webhook receiver endpoint in demo-tools for local notification demonstration (FR4).
 * Acknowledges receipt and logs only safe metadata (approval ID, tool name, status).
 * Avoids logging raw request bodies or user/argument data.
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    public record ApprovalNotification(@NotBlank String approvalId, @NotBlank String toolName,
                                       @NotBlank String status) {}

    @PostMapping("/approvals")
    public ResponseEntity<Map<String, Object>> receiveApprovalWebhook(@Valid @RequestBody ApprovalNotification payload) {
        String approvalId = payload.approvalId();
        String toolName = payload.toolName();
        String status = payload.status();

        log.info("DemoTools: Received approval webhook notification: approvalId='{}', toolName='{}', status='{}'",
                approvalId, toolName, status);

        return ResponseEntity.ok(Map.of(
                "status", "ACKNOWLEDGED",
                "approvalId", approvalId
        ));
    }
}
