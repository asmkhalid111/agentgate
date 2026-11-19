package com.agentgate.gateway.approval;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = {
        "agentgate.approval.expiration-interval-ms=100",
        "agentgate.approval.expiration-initial-delay-ms=50"
})
@DisplayName("Automatic Approval Timeout Expiration Tests (FR4)")
class ApprovalExpirationSchedulerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalRequestRepository repository;

    @Autowired
    private ApprovalService approvalService;

    private static final String HUMAN_APPROVER_USERNAME = "officer.smith";

    private org.springframework.test.web.servlet.request.RequestPostProcessor humanApproverJwt() {
        return jwt()
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_APPROVER"))
                .jwt(j -> j
                        .subject("usr-uuid-101")
                        .claim("preferred_username", HUMAN_APPROVER_USERNAME)
                        .claim("email", "officer.smith@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("APPROVER")))
                );
    }

    @Test
    @DisplayName("1. Overdue PENDING requests are automatically transitioned to EXPIRED by the background scheduler (Correction A)")
    void automaticBackgroundSweep_transitionsOverdueRequestsToExpired() throws Exception {
        String id = "appr-sched-auto-" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRequest overdue = new ApprovalRequest(
                id,
                "agent-finance-bot",
                "usr-1",
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":5000.0,\"orderId\":\"ORD-AUTO\"}",
                null,
                ApprovalStatus.PENDING,
                now.minus(20, ChronoUnit.MINUTES),
                now.minus(5, ChronoUnit.MINUTES), // Expired 5 minutes ago
                null,
                null,
                null
        );
        repository.save(overdue);

        // Bounded polling wait: Verify background scheduler automatically transitions the request without manual invocation
        long deadline = System.currentTimeMillis() + 3000;
        ApprovalRequest current = null;
        while (System.currentTimeMillis() < deadline) {
            current = repository.findById(id).orElse(null);
            if (current != null && current.status() == ApprovalStatus.EXPIRED) {
                break;
            }
            Thread.sleep(50);
        }

        assertNotNull(current, "Request must be present in repository");
        assertEquals(ApprovalStatus.EXPIRED, current.status(),
                "Overdue request must be automatically transitioned to EXPIRED by the background scheduler");
        assertEquals("SYSTEM", current.decidedBy(), "System attribution must be recorded");
        assertEquals("Approval request expired after timeout", current.decisionReason());
        assertNotNull(current.decidedAt(), "Decision time must be recorded");
    }

    @Test
    @DisplayName("2. Requests whose deadline has not passed remain PENDING during scheduler sweeps")
    void activeRequests_remainPendingDuringSchedulerSweeps() throws Exception {
        String id = "appr-sched-active-" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRequest active = new ApprovalRequest(
                id,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":100.0,\"orderId\":\"ORD-ACTIVE\"}",
                null,
                ApprovalStatus.PENDING,
                now,
                now.plus(15, ChronoUnit.MINUTES), // Expires in 15 minutes
                null,
                null,
                null
        );
        repository.save(active);

        // Wait ~250ms to allow the 100ms background scheduler to fire multiple times
        Thread.sleep(250);

        ApprovalRequest current = repository.findById(id).orElseThrow();
        assertEquals(ApprovalStatus.PENDING, current.status(), "Active request must remain PENDING");
        assertNull(current.decidedAt());
        assertNull(current.decidedBy());
    }

    @Test
    @DisplayName("3. Expired requests are visible through REST API and disappear from default PENDING queue")
    void expiredRequests_visibleInRestApi_andDisappearFromDefaultPendingQueue() throws Exception {
        String id = "appr-sched-rest-" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRequest overdue = new ApprovalRequest(
                id,
                "agent-finance-bot",
                "usr-rest",
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":999.0,\"orderId\":\"ORD-REST\"}",
                null,
                ApprovalStatus.PENDING,
                now.minus(30, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES),
                null, null, null
        );
        repository.save(overdue);

        // Wait for scheduler to transition it
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            ApprovalRequest req = repository.findById(id).orElse(null);
            if (req != null && req.status() == ApprovalStatus.EXPIRED) {
                break;
            }
            Thread.sleep(50);
        }

        // 3a. Accessible via GET /api/v1/approvals/{id}
        mockMvc.perform(get("/api/v1/approvals/" + id)
                        .with(humanApproverJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id)))
                .andExpect(jsonPath("$.status", is("EXPIRED")))
                .andExpect(jsonPath("$.decidedBy", is("SYSTEM")))
                .andExpect(jsonPath("$.decisionReason", is("Approval request expired after timeout")))
                .andExpect(jsonPath("$.decidedAt", notNullValue()));

        // 3b. Disappears from default PENDING queue (GET /api/v1/approvals)
        mockMvc.perform(get("/api/v1/approvals")
                        .with(humanApproverJwt())
                        .param("status", "PENDING")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id))));

        // 3c. Visible under explicit status=EXPIRED filter (GET /api/v1/approvals?status=EXPIRED)
        mockMvc.perform(get("/api/v1/approvals")
                        .with(humanApproverJwt())
                        .param("status", "EXPIRED")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id)));
    }

    @Test
    @DisplayName("4. Approval after timeout returns HTTP 409 Conflict (Correction B)")
    void approve_afterExpiration_returns409Conflict() throws Exception {
        String id = "appr-sched-approve-409-" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRequest overdue = new ApprovalRequest(
                id,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":7000.0,\"orderId\":\"ORD-LATE-APP\"}",
                null,
                ApprovalStatus.PENDING,
                now.minus(25, ChronoUnit.MINUTES),
                now.minus(5, ChronoUnit.MINUTES),
                null, null, null
        );
        repository.save(overdue);

        // Await background scheduler transition
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            ApprovalRequest req = repository.findById(id).orElse(null);
            if (req != null && req.status() == ApprovalStatus.EXPIRED) {
                break;
            }
            Thread.sleep(50);
        }

        mockMvc.perform(post("/api/v1/approvals/" + id + "/approve")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Invalid Approval State Transition")))
                .andExpect(jsonPath("$.detail", containsString("expired")));

        // Status must remain EXPIRED, never APPROVED
        ApprovalRequest after = repository.findById(id).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, after.status());
    }

    @Test
    @DisplayName("5. Rejection after timeout returns HTTP 409 Conflict (Correction B)")
    void reject_afterExpiration_returns409Conflict() throws Exception {
        String id = "appr-sched-reject-409-" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRequest overdue = new ApprovalRequest(
                id,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":8000.0,\"orderId\":\"ORD-LATE-REJ\"}",
                null,
                ApprovalStatus.PENDING,
                now.minus(25, ChronoUnit.MINUTES),
                now.minus(5, ChronoUnit.MINUTES),
                null, null, null
        );
        repository.save(overdue);

        // Await background scheduler transition
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            ApprovalRequest req = repository.findById(id).orElse(null);
            if (req != null && req.status() == ApprovalStatus.EXPIRED) {
                break;
            }
            Thread.sleep(50);
        }

        mockMvc.perform(post("/api/v1/approvals/" + id + "/reject")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Late rejection attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Invalid Approval State Transition")))
                .andExpect(jsonPath("$.detail", containsString("expired")));

        ApprovalRequest after = repository.findById(id).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, after.status());
    }

    @Test
    @DisplayName("6. Overdue backlog from simulated restart is swept during initial sweep")
    void simulatedRestart_overdueBacklogSweptDuringInitialSweep() {
        String id = "appr-sched-backlog-" + System.currentTimeMillis();
        Instant now = Instant.now();
        ApprovalRequest backlogItem = new ApprovalRequest(
                id,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":1200.0,\"orderId\":\"ORD-BACKLOG\"}",
                null,
                ApprovalStatus.PENDING,
                now.minus(60, ChronoUnit.MINUTES),
                now.minus(45, ChronoUnit.MINUTES), // Lapsed 45 minutes ago during simulated downtime
                null, null, null
        );
        repository.save(backlogItem);

        // Immediate sweep execution (as performed by initialDelay on startup)
        int swept = approvalService.expirePendingRequests();
        assertTrue(swept >= 1, "At least one overdue request from backlog must be swept");

        ApprovalRequest current = repository.findById(id).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, current.status());
        assertEquals("SYSTEM", current.decidedBy());
    }

    @Test
    @DisplayName("7. Lazy SQL check prevents approval even before background scheduler sweep runs (Correction B)")
    void lazySqlCheck_preventsApprovalBeforeSchedulerSweepRuns() throws Exception {
        String id = "appr-lazy-check-" + System.currentTimeMillis();
        Instant now = Instant.now();
        // Request expired 10 seconds ago
        ApprovalRequest overdue = new ApprovalRequest(
                id,
                "agent-finance-bot",
                null,
                "orders_refund",
                "pol-large-refund",
                "{\"amount\":3000.0,\"orderId\":\"ORD-LAZY\"}",
                null,
                ApprovalStatus.PENDING,
                now.minus(16, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.SECONDS),
                null, null, null
        );
        repository.save(overdue);

        // Immediately attempt approval via REST API before background sweep
        mockMvc.perform(post("/api/v1/approvals/" + id + "/approve")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Invalid Approval State Transition")))
                .andExpect(jsonPath("$.detail", containsString("expired")));

        // Atomic CAS transitioned it directly to EXPIRED
        ApprovalRequest current = repository.findById(id).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, current.status());
    }
}
