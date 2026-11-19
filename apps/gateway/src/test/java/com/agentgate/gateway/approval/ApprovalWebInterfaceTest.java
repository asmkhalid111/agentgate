package com.agentgate.gateway.approval;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Human Approval Web Interface Integration Tests (FR4)")
class ApprovalWebInterfaceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApprovalService approvalService;

    @Autowired
    private ApprovalRequestRepository repository;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String HUMAN_APPROVER_USERNAME = "officer.smith";

    private RequestPostProcessor humanApproverOidc() {
        return oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))
                .idToken(token -> token
                        .subject("usr-uuid-101")
                        .claim("preferred_username", HUMAN_APPROVER_USERNAME)
                        .claim("email", "officer.smith@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("APPROVER")))
                );
    }

    private RequestPostProcessor nonApproverHumanOidc() {
        return oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .idToken(token -> token
                        .subject("usr-uuid-102")
                        .claim("preferred_username", "standard.employee")
                        .claim("email", "standard@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("USER")))
                );
    }

    private RequestPostProcessor agentServiceAccountOidc() {
        return oidcLogin()
                .authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))
                .idToken(token -> token
                        .subject("service-account-agent-finance-bot")
                        .claim("preferred_username", "service-account-agent-finance-bot")
                        .claim("azp", "agent-finance-bot")
                        .claim("realm_access", Map.of("roles", List.of("APPROVER")))
                );
    }

    @Test
    @DisplayName("1. Unauthenticated request to /approvals redirects to Keycloak OAuth2 login")
    void unauthenticated_approvalsRoute_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/approvals"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/keycloak")));
    }

    @Test
    @DisplayName("2. Unauthenticated request to /approvals.html is protected and redirects to login")
    void unauthenticated_approvalsHtml_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/approvals.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/keycloak")));
    }

    @Test
    @DisplayName("3. Authenticated human approver accesses /approvals and forwards to approvals.html")
    void authenticatedApprover_accessesApprovalsPage() throws Exception {
        mockMvc.perform(get("/approvals").with(humanApproverOidc()))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/approvals.html"));
    }

    @Test
    @DisplayName("4. Authenticated human approver can access /approvals.html directly")
    void authenticatedApprover_accessesApprovalsHtmlDirectly() throws Exception {
        mockMvc.perform(get("/approvals.html").with(humanApproverOidc()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("5. Authenticated human approver lists pending approvals via /approvals/api/requests")
    void authenticatedApprover_listsPendingRequests() throws Exception {
        approvalService.createRequest("agent-finance-bot", "usr-web-1", "orders_refund", "pol-web-1",
                Map.of("orderId", "ORD-WEB-P1", "amount", 1250.0), Duration.ofMinutes(15));

        mockMvc.perform(get("/approvals/api/requests")
                        .with(humanApproverOidc())
                        .param("status", "PENDING")
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
                .andExpect(jsonPath("$.content[0].toolName", notNullValue()))
                .andExpect(jsonPath("$.content[0].agentId", notNullValue()));
    }

    @Test
    @DisplayName("6. Non-approver human user receives 403 Forbidden on /approvals and /approvals/api/requests")
    void nonApproverHuman_accessDenied() throws Exception {
        mockMvc.perform(get("/approvals").with(nonApproverHumanOidc()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/approvals.html").with(nonApproverHumanOidc()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/approvals/api/requests").with(nonApproverHumanOidc()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("7. Agent service-account attempting /approvals/api/requests is rejected with 403")
    void agentServiceAccount_accessDenied() throws Exception {
        mockMvc.perform(get("/approvals/api/requests")
                        .with(agentServiceAccountOidc())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("8. Approver views approval details via GET /approvals/api/requests/{id}")
    void approver_retrievesDetails() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", "usr-web-det", "orders_refund", "pol-web-detail",
                Map.of("orderId", "ORD-DET-99", "amount", 5500.0), Duration.ofMinutes(15));

        mockMvc.perform(get("/approvals/api/requests/" + request.id())
                        .with(humanApproverOidc())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(request.id())))
                .andExpect(jsonPath("$.toolName", is("orders_refund")))
                .andExpect(jsonPath("$.originalArguments.orderId", is("ORD-DET-99")))
                .andExpect(jsonPath("$.originalArguments.amount", is(5500.0)));
    }

    @Test
    @DisplayName("9. Approver approves without edits via POST /approvals/api/requests/{id}/approve with CSRF")
    void approver_approvesWithoutEdits_success() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-web-appr",
                Map.of("orderId", "ORD-APP-OK", "amount", 1200.0), Duration.ofMinutes(15));

        mockMvc.perform(post("/approvals/api/requests/" + request.id() + "/approve")
                        .with(humanApproverOidc())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(request.id())))
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedBy", is(HUMAN_APPROVER_USERNAME)))
                .andExpect(jsonPath("$.approvedArguments.amount", is(1200.0)));
    }

    @Test
    @DisplayName("10. Missing CSRF token on POST /approvals/api/requests/{id}/approve returns 403 Forbidden")
    void missingCsrf_failsWithForbidden() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-web-csrf",
                Map.of("orderId", "ORD-NO-CSRF", "amount", 900.0), Duration.ofMinutes(15));

        // Note: No .with(csrf()) here
        mockMvc.perform(post("/approvals/api/requests/" + request.id() + "/approve")
                        .with(humanApproverOidc())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("11. Approver approves with edited arguments via POST /approvals/api/requests/{id}/approve")
    void approver_approvesWithEditedArguments_success() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-web-edit",
                Map.of("orderId", "ORD-EDIT", "amount", 5000.0), Duration.ofMinutes(15));

        String body = """
                {
                  "editedArguments": {
                    "orderId": "ORD-EDIT",
                    "amount": 2500.0
                  }
                }
                """;

        mockMvc.perform(post("/approvals/api/requests/" + request.id() + "/approve")
                        .with(humanApproverOidc())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(request.id())))
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedBy", is(HUMAN_APPROVER_USERNAME)))
                .andExpect(jsonPath("$.originalArguments.amount", is(5000.0)))
                .andExpect(jsonPath("$.approvedArguments.amount", is(2500.0)));
    }

    @Test
    @DisplayName("12. Approver rejects request with reason via POST /approvals/api/requests/{id}/reject")
    void approver_rejectsWithReason_success() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-web-rej",
                Map.of("orderId", "ORD-REJ", "amount", 999.0), Duration.ofMinutes(15));

        String body = """
                {
                  "reason": "Policy violation: refund exceeds daily limit"
                }
                """;

        mockMvc.perform(post("/approvals/api/requests/" + request.id() + "/reject")
                        .with(humanApproverOidc())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(request.id())))
                .andExpect(jsonPath("$.status", is("REJECTED")))
                .andExpect(jsonPath("$.decidedBy", is(HUMAN_APPROVER_USERNAME)))
                .andExpect(jsonPath("$.decisionReason", is("Policy violation: refund exceeds daily limit")));
    }

    @Test
    @DisplayName("13. Approver rejects request without reason fails with 400 Bad Request")
    void approver_rejectWithoutReason_failsValidation() throws Exception {
        ApprovalRequest request = approvalService.createRequest(
                "agent-finance-bot", null, "orders_refund", "pol-web-rej-fail",
                Map.of("orderId", "ORD-FAIL", "amount", 50.0), Duration.ofMinutes(15));

        mockMvc.perform(post("/approvals/api/requests/" + request.id() + "/reject")
                        .with(humanApproverOidc())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }

    @Test
    @DisplayName("14. Decision on EXPIRED request fails with 409 Conflict")
    void decisionOnExpiredRequest_failsConflict() throws Exception {
        String expiredId = "appr-expired-web-" + System.currentTimeMillis();
        ApprovalRequest overdue = new ApprovalRequest(
                expiredId, "agent-finance-bot", null, "orders_refund", "pol-web-exp",
                "{}", null, ApprovalStatus.PENDING,
                Instant.now().minus(20, ChronoUnit.MINUTES),
                Instant.now().minus(5, ChronoUnit.MINUTES),
                null, null, null
        );
        repository.save(overdue);

        mockMvc.perform(post("/approvals/api/requests/" + expiredId + "/approve")
                        .with(humanApproverOidc())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("INVALID_APPROVAL_STATE")));
    }

    @Test
    @DisplayName("15. OAuth2 login initiation redirects to Keycloak authorization endpoint with OIDC parameters")
    void oauth2LoginInitiation_redirectsToKeycloakAuthEndpoint() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/keycloak"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", allOf(
                        containsString("http://localhost:8180/realms/agentgate/protocol/openid-connect/auth"),
                        containsString("response_type=code"),
                        containsString("client_id=agentgate-web"),
                        containsString("scope=openid"),
                        containsString("redirect_uri=")
                )));
    }

    @Test
    @DisplayName("16. OAuth2 callback is covered by OAuth2 authentication filter and fails safely without state")
    void oauth2Callback_coveredByFilter_failsSafelyWithoutState() throws Exception {
        // Invoking the callback route without prior session state must be intercepted by OAuth2LoginAuthenticationFilter
        // and fail safely (e.g. redirect to login or error, not 404 or unhandled bypass)
        mockMvc.perform(get("/login/oauth2/code/keycloak"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("17. Login route /login is accessible without authentication")
    void loginRoute_accessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("18. Unrelated routes remain protected by the catch-all chain (denyAll)")
    void unrelatedRoutes_protectedByCatchAllChain() throws Exception {
        // Unauthenticated access to arbitrary unmapped path redirects to login
        mockMvc.perform(get("/unrelated/system-admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/keycloak")));

        // Authenticated non-approver accessing unmapped path receives 403 Forbidden
        mockMvc.perform(get("/unrelated/system-admin").with(nonApproverHumanOidc()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("19. Machine JWT Bearer authentication on @Order(1) chain continues working")
    void machineJwtAuthentication_continuesWorking() throws Exception {
        mockMvc.perform(get("/api/v1/approvals")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))
                                .jwt(j -> j
                                        .subject("usr-machine-jwt")
                                        .claim("preferred_username", "officer.machine")
                                        .claim("realm_access", Map.of("roles", List.of("APPROVER")))
                                ))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", notNullValue()));
    }
}
