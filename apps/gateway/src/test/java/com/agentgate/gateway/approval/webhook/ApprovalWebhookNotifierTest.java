package com.agentgate.gateway.approval.webhook;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.event.ApprovalCreatedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@DisplayName("Approval Webhook Notifier Unit Tests (FR4)")
class ApprovalWebhookNotifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApprovalRequest sampleRequest() {
        return new ApprovalRequest(
                "appr-test-1234",
                "agent-finance-bot",
                "sensitive-user-alice", // userId must NOT be in webhook payload
                "orders_refund",
                "rule-financial-limit",
                "{\"orderId\":\"ORD-999\",\"amount\":5000,\"secretToken\":\"pass123\"}", // arguments must NOT be in payload
                null,
                ApprovalStatus.PENDING,
                Instant.parse("2026-11-19T10:00:00Z"),
                Instant.parse("2026-11-19T10:15:00Z"),
                null,
                null,
                null
        );
    }

    @Test
    @DisplayName("1. Configured receiver receives valid JSON POST with exact schema")
    void sendWebhook_successfulPostWithExpectedSchema() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApprovalWebhookProperties props = new ApprovalWebhookProperties(
                true,
                "http://test-receiver:8082/api/webhook/approvals",
                1000,
                1000
        );

        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props, restClient);
        ApprovalRequest request = sampleRequest();

        AtomicReference<String> capturedBody = new AtomicReference<>();

        server.expect(requestTo("http://test-receiver:8082/api/webhook/approvals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", containsString("application/json")))
                .andExpect(req -> capturedBody.set(req.getBody().toString()))
                .andRespond(withSuccess("{\"status\":\"ACKNOWLEDGED\"}", MediaType.APPLICATION_JSON));

        boolean delivered = notifier.sendWebhook(request);

        assertTrue(delivered, "Webhook delivery should return true on 2xx");
        server.verify();

        assertNotNull(capturedBody.get());
    }

    @Test
    @DisplayName("2. Minimal payload contains required metadata and NO userId, arguments, or credentials (Reviewer Correction C)")
    void sendWebhook_payloadStrictlyOmitsSensitiveFields() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApprovalWebhookProperties props = new ApprovalWebhookProperties(
                true,
                "http://test-receiver:8082/api/webhook/approvals",
                1000,
                1000
        );

        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props, restClient);
        ApprovalRequest request = sampleRequest();

        AtomicReference<String> capturedBody = new AtomicReference<>();

        server.expect(requestTo("http://test-receiver:8082/api/webhook/approvals"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request1 -> capturedBody.set(request1.getBody().toString()))
                .andRespond(withSuccess());

        notifier.sendWebhook(request);
        server.verify();

        JsonNode json = objectMapper.readTree(capturedBody.get());

        // Required minimal operational fields
        assertEquals("appr-test-1234", json.get("approvalId").asText());
        assertEquals("agent-finance-bot", json.get("agentId").asText());
        assertEquals("orders_refund", json.get("toolName").asText());
        assertEquals("PENDING", json.get("status").asText());
        assertEquals("2026-11-19T10:00:00Z", json.get("createdAt").asText());
        assertEquals("2026-11-19T10:15:00Z", json.get("expiresAt").asText());
        assertEquals("rule-financial-limit", json.get("policyRuleId").asText());

        // Strictly forbidden fields
        assertNull(json.get("userId"), "userId MUST NOT be included in webhook payload");
        assertNull(json.get("originalArguments"), "Arguments MUST NOT be in webhook payload");
        assertNull(json.get("originalArgumentsJson"), "Arguments JSON MUST NOT be in webhook payload");
        assertNull(json.get("arguments"), "Arguments MUST NOT be in webhook payload");
        assertNull(json.get("secretToken"), "Credentials MUST NOT be in webhook payload");
        assertNull(json.get("headers"), "Headers MUST NOT be in webhook payload");
        assertNull(json.get("token"), "Tokens MUST NOT be in webhook payload");
    }

    @Test
    @DisplayName("3. HTTP 500 error from receiver is caught safely without propagating exception")
    void sendWebhook_serverErrorCaughtSafely() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApprovalWebhookProperties props = new ApprovalWebhookProperties(
                true,
                "http://test-receiver:8082/api/webhook/approvals",
                1000,
                1000
        );

        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props, restClient);

        server.expect(requestTo("http://test-receiver:8082/api/webhook/approvals"))
                .andRespond(withServerError());

        // Must not throw any exception
        assertDoesNotThrow(() -> {
            boolean result = notifier.sendWebhook(sampleRequest());
            assertFalse(result, "Delivery should return false when receiver returns 500");
        });

        server.verify();
    }

    @Test
    @DisplayName("4. Network timeout is caught safely without propagating exception")
    void sendWebhook_timeoutCaughtSafely() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApprovalWebhookProperties props = new ApprovalWebhookProperties(
                true,
                "http://test-receiver:8082/api/webhook/approvals",
                1000,
                1000
        );

        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props, restClient);

        server.expect(requestTo("http://test-receiver:8082/api/webhook/approvals"))
                .andRespond(request -> {
                    throw new RuntimeException("Connection timed out", new SocketTimeoutException("connect timed out"));
                });

        assertDoesNotThrow(() -> {
            boolean result = notifier.sendWebhook(sampleRequest());
            assertFalse(result, "Delivery should return false on network timeout");
        });

        server.verify();
    }

    @Test
    @DisplayName("5. Disabled webhook sends no HTTP request")
    void sendWebhook_disabled_sendsNoRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApprovalWebhookProperties props = new ApprovalWebhookProperties(
                false, // disabled
                "http://test-receiver:8082/api/webhook/approvals",
                1000,
                1000
        );

        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props, restClient);

        boolean result = notifier.sendWebhook(sampleRequest());

        assertFalse(result);
        server.verify(); // Verifies 0 requests made
    }

    @Test
    @DisplayName("6. Blank target URL sends no HTTP request")
    void sendWebhook_blankUrl_sendsNoRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        ApprovalWebhookProperties props = new ApprovalWebhookProperties(
                true,
                "   ", // blank URL
                1000,
                1000
        );

        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props, restClient);

        boolean result = notifier.sendWebhook(sampleRequest());

        assertFalse(result);
        server.verify();
    }

    @Test
    @DisplayName("7. Event listener safely handles null event or request")
    void onApprovalCreated_nullEvent_handlesSafely() {
        ApprovalWebhookProperties props = new ApprovalWebhookProperties(true, "http://valid:8080", 1000, 1000);
        ApprovalWebhookNotifier notifier = new ApprovalWebhookNotifier(props);

        assertDoesNotThrow(() -> notifier.onApprovalCreated(null));
        assertDoesNotThrow(() -> notifier.onApprovalCreated(new ApprovalCreatedEvent(null)));
    }
}
