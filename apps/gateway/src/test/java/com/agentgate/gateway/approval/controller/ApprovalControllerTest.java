package com.agentgate.gateway.approval.controller;

import com.agentgate.gateway.approval.domain.ApprovalRequest;
import com.agentgate.gateway.approval.domain.ApprovalStatus;
import com.agentgate.gateway.approval.repository.ApprovalRequestRepository;
import com.agentgate.gateway.approval.service.ApprovalService;
import com.agentgate.gateway.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Human Approval REST API Integration Tests (FR4)")
class ApprovalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

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

    private org.springframework.test.web.servlet.request.RequestPostProcessor agentServiceAccountJwt() {
        return jwt()
                .authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_APPROVER"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_tool:admin")
                )
                .jwt(j -> j
                        .subject("service-account-agent-finance-bot")
                        .claim("preferred_username", "service-account-agent-finance-bot")
                        .claim("azp", "agent-finance-bot")
                        .claim("realm_access", Map.of("roles", List.of("APPROVER", "ADMIN")))
                        .claim("scope", "tool:admin agent:invoke")
                );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor nonApproverHumanJwt() {
        return jwt()
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j
                        .subject("usr-uuid-102")
                        .claim("preferred_username", "standard.employee")
                        .claim("email", "standard@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("USER")))
                );
    }

    @Test
    @DisplayName("1. Human approver lists pending requests with pagination (GET /api/v1/approvals)")
    void listApprovals_humanApprover_returnsPaginatedPendingList() throws Exception {
        approvalService.createRequest("agent-finance-bot", "usr-1", "orders_refund", "pol-1",
                Map.of("orderId", "ORD-P1", "amount", 1000.0), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/v1/approvals")
                        .with(humanApproverJwt())
                        .param("page", "0")
                        .param("size", "10")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", notNullValue()))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.content[0].id", notNullValue()))
                .andExpect(jsonPath("$.content[0].status", is("PENDING")))
                .andExpect(jsonPath("$.content[0].originalArguments").doesNotExist()); // Verification of C: Summaries omit heavy args
    }

    @Test
    @DisplayName("2. Human approver retrieves approval details (GET /api/v1/approvals/{id})")
    void getApproval_humanApprover_returnsStructuredDetails() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", "usr-2", "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-DETAIL", "amount", 7500.0), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/v1/approvals/" + request.id())
                        .with(humanApproverJwt())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(request.id())))
                .andExpect(jsonPath("$.agentId", is("agent-finance-bot")))
                .andExpect(jsonPath("$.toolName", is("orders_refund")))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.originalArguments.orderId", is("ORD-DETAIL")))
                .andExpect(jsonPath("$.originalArguments.amount", is(7500.0)));
    }

    @Test
    @DisplayName("3. Human approver approves without edits (POST /api/v1/approvals/{id}/approve)")
    void approve_withoutEdits_transitionsToApproved() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-APP-1", "amount", 3000.0), Duration.ofMinutes(15));

        mockMvc.perform(post("/api/v1/approvals/" + request.id() + "/approve")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(request.id())))
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedBy", is(HUMAN_APPROVER_USERNAME)))
                .andExpect(jsonPath("$.decidedAt", notNullValue()))
                .andExpect(jsonPath("$.approvedArguments.amount", is(3000.0)));
    }

    @Test
    @DisplayName("4. Human approver approves with edited arguments (POST /api/v1/approvals/{id}/approve)")
    void approve_withEditedArguments_preservesOriginalAndStoresEdited() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-APP-2", "amount", 5000.0), Duration.ofMinutes(15));

        String body = """
                {
                  "editedArguments": {
                    "orderId": "ORD-APP-2",
                    "amount": 2000.0,
                    "reviewerNote": "Reduced refund amount"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/approvals/" + request.id() + "/approve")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedBy", is(HUMAN_APPROVER_USERNAME)))
                .andExpect(jsonPath("$.originalArguments.amount", is(5000.0)))
                .andExpect(jsonPath("$.approvedArguments.amount", is(2000.0)))
                .andExpect(jsonPath("$.approvedArguments.reviewerNote", is("Reduced refund amount")));
    }

    @Test
    @DisplayName("5. Human approver rejects with a valid reason (POST /api/v1/approvals/{id}/reject)")
    void reject_withReason_transitionsToRejected() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-REJ-1", "amount", 9000.0), Duration.ofMinutes(15));

        String body = "{\"reason\":\"Customer order not delivered yet\"}";

        mockMvc.perform(post("/api/v1/approvals/" + request.id() + "/reject")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.decidedBy", is(HUMAN_APPROVER_USERNAME)))
                .andExpect(jsonPath("$.decisionReason", is("Customer order not delivered yet")));
    }

    @Test
    @DisplayName("6. Blank rejection reason returns 400 Bad Request ProblemDetail")
    void reject_blankReason_returns400ProblemDetail() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-REJ-BLANK", "amount", 9000.0), Duration.ofMinutes(15));

        String body = "{\"reason\":\"   \"}";

        mockMvc.perform(post("/api/v1/approvals/" + request.id() + "/reject")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.title", is("Validation Failed")));
    }

    @Test
    @DisplayName("7. Agent service account with APPROVER/ADMIN roles receives 403 Forbidden (Reviewer Correction A)")
    void agentServiceAccount_withApproverRole_receives403Forbidden() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-AGENT-BLOCKED", "amount", 5000.0), Duration.ofMinutes(15));

        mockMvc.perform(post("/api/v1/approvals/" + request.id() + "/approve")
                        .with(agentServiceAccountJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.title", is("Access Denied")));
    }

    @Test
    @DisplayName("8. Non-approver human user receives 403 Forbidden")
    void nonApproverHuman_receives403Forbidden() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-NON-APPROVER", "amount", 5000.0), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/v1/approvals/" + request.id())
                        .with(nonApproverHumanJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("9. Unauthenticated caller receives 401 Unauthorized")
    void unauthenticatedCaller_receives401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/approvals"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("10. Expired or repeated decision returns 409 Conflict ProblemDetail (Reviewer Correction B)")
    void expiredOrRepeatedDecision_returns409ConflictProblemDetail() throws Exception {
        // Case A: Repeated approval
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-REPEAT", "amount", 5000.0), Duration.ofMinutes(15));

        approvalService.approve(request.id(), "prior.approver", null);

        mockMvc.perform(post("/api/v1/approvals/" + request.id() + "/approve")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Invalid Approval State Transition")))
                .andExpect(jsonPath("$.detail", containsString("APPROVED")));

        // Case B: Expired request
        String expiredId = "appr-expired-api-" + System.currentTimeMillis();
        ApprovalRequest overdue = new ApprovalRequest(
                expiredId, "agent-finance-bot", null, "orders_refund", "pol-1",
                "{}", null, ApprovalStatus.PENDING,
                Instant.now().minus(20, ChronoUnit.MINUTES),
                Instant.now().minus(5, ChronoUnit.MINUTES),
                null, null, null
        );
        repository.save(overdue);

        mockMvc.perform(post("/api/v1/approvals/" + expiredId + "/approve")
                        .with(humanApproverJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.title", is("Invalid Approval State Transition")))
                .andExpect(jsonPath("$.detail", containsString("expired")));
    }

    @Test
    @DisplayName("11. Unknown approval ID returns 404 Not Found ProblemDetail (Reviewer Correction B)")
    void unknownApprovalId_returns404NotFoundProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/approvals/appr-nonexistent-999")
                        .with(humanApproverJwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.title", is("Approval Request Not Found")))
                .andExpect(jsonPath("$.detail", containsString("appr-nonexistent-999")));
    }

    @Test
    @DisplayName("12. Agent service account with APPROVER role receives 403 Forbidden on GET /api/v1/approvals/{id}")
    void agentServiceAccount_onGetApprovalById_receives403Forbidden() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-large-refund",
                Map.of("orderId", "ORD-AGENT-GET-BLOCKED", "amount", 5000.0), Duration.ofMinutes(15));

        mockMvc.perform(get("/api/v1/approvals/" + request.id())
                        .with(agentServiceAccountJwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.title", is("Access Denied")));
    }
}
