package com.agentgate.gateway.approval.webhook;

import com.agentgate.gateway.common.DemoServiceCredential;
import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.event.ApprovalCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

/**
 * Asynchronous, non-blocking webhook notification dispatcher (FR4 / Reviewer Corrections A & B).
 * <p>
 * Listens for {@link ApprovalCreatedEvent} delivered strictly after database transaction commit
 * ({@link TransactionPhase#AFTER_COMMIT}). Dispatches minimal notification metadata over HTTP
 * using Spring {@link RestClient} on a dedicated bounded executor thread pool.
 * <p>
 * Fail-Safe Invariants:
 * <ul>
 *   <li>Best-effort delivery: HTTP or network errors are caught and logged safely without rethrowing.</li>
 *   <li>Never rolls back or invalidates the persisted {@code PENDING} approval record.</li>
 *   <li>Never executes the downstream tool or unblocks restricted calls.</li>
 *   <li>Zero PII leakage: never transmits or logs raw tool arguments, credentials, or user identifiers.</li>
 * </ul>
 */
@Component
@EnableConfigurationProperties(ApprovalWebhookProperties.class)
public class ApprovalWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhookNotifier.class);

    private final ApprovalWebhookProperties properties;
    private final RestClient restClient;

    private DemoServiceCredential demoCredential = new DemoServiceCredential("", "");

    @Autowired
    void setDemoCredential(DemoServiceCredential demoCredential) {
        this.demoCredential = demoCredential;
    }

    @Autowired
    public ApprovalWebhookNotifier(ApprovalWebhookProperties properties) {
        this(properties, createDefaultRestClient(properties));
    }

    public ApprovalWebhookNotifier(ApprovalWebhookProperties properties, RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    private static RestClient createDefaultRestClient(ApprovalWebhookProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeoutDuration()).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.getReadTimeoutDuration());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Handles approval creation event asynchronously after transaction commit.
     */
    @Async("webhookTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalCreated(ApprovalCreatedEvent event) {
        if (event == null || event.request() == null) {
            return;
        }
        sendWebhook(event.request());
    }

    /**
     * Sends the minimal webhook notification to the operator-configured destination.
     *
     * @param request the newly created approval request
     * @return true if notification was dispatched and acknowledged (HTTP 2xx), false otherwise
     */
    public boolean sendWebhook(ApprovalRequest request) {
        if (!properties.enabled()) {
            log.debug("Approval webhook notification skipped: webhook is disabled");
            return false;
        }

        String targetUrl = properties.url();
        if (targetUrl == null || targetUrl.isBlank()) {
            log.debug("Approval webhook notification skipped: webhook URL is not configured");
            return false;
        }

        ApprovalWebhookPayload payload = new ApprovalWebhookPayload(
                request.id(),
                request.agentId(),
                request.toolName(),
                request.status().name(),
                request.createdAt(),
                request.expiresAt(),
                request.policyRuleId()
        );

        try {
            URI target = URI.create(targetUrl);
            var response = restClient.post()
                    .uri(target)
                    .headers(headers -> demoCredential.apply(target, headers::set))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("Approval webhook to '{}' returned {}", targetUrl, response.getStatusCode());
                return false;
            }
            log.info("Approval webhook delivered successfully for request '{}' to '{}'",
                    payload.approvalId(), targetUrl);
            return true;
        } catch (Exception e) {
            // Failure safety: Best-effort delivery. Catch all HTTP/network exceptions.
            // Never propagate exceptions, never disrupt the transaction or execution pipeline.
            log.warn("Failed to deliver approval webhook for request '{}' to '{}': {}",
                    payload.approvalId(), targetUrl, e.getMessage());
            return false;
        }
    }
}
