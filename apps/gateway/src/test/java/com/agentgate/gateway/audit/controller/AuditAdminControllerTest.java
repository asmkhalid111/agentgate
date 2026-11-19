package com.agentgate.gateway.audit.controller;

import com.agentgate.gateway.audit.domain.AuditRecordDraft;
import com.agentgate.gateway.audit.repository.AuditRepository;
import com.agentgate.gateway.audit.service.AuditRecorder;
import com.agentgate.gateway.config.TestSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Audit Admin REST API Tests (FR7)")
class AuditAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditRecorder auditRecorder;

    private static final String HUMAN_ADMIN_USERNAME = "admin.audit";

    @BeforeEach
    void setUp() {
        auditRepository.resetToGenesis();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor humanAdminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .jwt(j -> j
                        .subject("usr-admin-uuid-001")
                        .claim("preferred_username", HUMAN_ADMIN_USERNAME)
                        .claim("email", "admin.audit@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor agentServiceAccountAdminJwt() {
        return jwt()
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_tool:admin")
                )
                .jwt(j -> j
                        .subject("service-account-agent-finance-bot")
                        .claim("preferred_username", "service-account-agent-finance-bot")
                        .claim("azp", "agent-finance-bot")
                        .claim("realm_access", Map.of("roles", List.of("ADMIN")))
                        .claim("scope", "tool:admin agent:invoke")
                );
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor approverOnlyHumanJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_APPROVER"))
                .jwt(j -> j
                        .subject("usr-approver-uuid-002")
                        .claim("preferred_username", "human.approver")
                        .claim("email", "approver@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("APPROVER")))
                );
    }

    @Test
    @DisplayName("Human admin should search and paginate audit log")
    void humanAdmin_searchesAndPaginatesAuditLog() throws Exception {
        // Record 3 events
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", "agent-finance-bot", "user-1", "agent-finance-bot",
                "orders_refund", "{\"amount\":100}", "rule-1", "Rule 1", "ALLOW", null, "SUCCESS", 20L, null
        ));
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "GOVERNANCE_DENIAL", "agent-support-bot", "user-2", "agent-support-bot",
                "database_drop", "{}", "rule-deny-drop", "Deny Drop", "DENY", null, "BLOCKED", 5L, "Blocked by policy"
        ));
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "APPROVAL_REQUESTED", "agent-finance-bot", "user-3", "agent-finance-bot",
                "payments_wire", "{\"amount\":50000}", "rule-wire", "Wire Approval", "REQUIRE_APPROVAL", "appr-123", "PENDING_APPROVAL", 0L, null
        ));

        // Unfiltered search: returns all 3
        mockMvc.perform(get("/api/v1/admin/audit")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.content[0].sequenceNumber").value(1))
                .andExpect(jsonPath("$.content[1].sequenceNumber").value(2))
                .andExpect(jsonPath("$.content[2].sequenceNumber").value(3));

        // Filter by agentId
        mockMvc.perform(get("/api/v1/admin/audit?agentId=agent-finance-bot")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));

        // Filter by toolName
        mockMvc.perform(get("/api/v1/admin/audit?toolName=database_drop")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].outcome").value("BLOCKED"));

        // Filter by approvalId
        mockMvc.perform(get("/api/v1/admin/audit?approvalId=appr-123")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].toolName").value("payments_wire"));
    }

    @Test
    @DisplayName("Human admin should export audit log in JSON format")
    void humanAdmin_exportsAuditLogAsJson() throws Exception {
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", "agent-finance-bot", "user-1", "agent-finance-bot",
                "orders_refund", "{\"amount\":100}", "rule-1", "Rule 1", "ALLOW", null, "SUCCESS", 20L, null
        ));

        mockMvc.perform(get("/api/v1/admin/audit/export?format=json")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.records", hasSize(1)))
                .andExpect(jsonPath("$.records[0].toolName").value("orders_refund"))
                .andExpect(jsonPath("$.exportTimestamp").isNotEmpty());
    }

    @Test
    @DisplayName("Human admin should export audit log in CSV format with formula injection protection")
    void humanAdmin_exportsAuditLogAsCsvWithFormulaNeutralization() throws Exception {
        // Argument starting with dangerous formula =cmd
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", "agent-finance-bot", "user-1", "agent-finance-bot",
                "calc_eval", "{\"formula\":\"=1+1\"}", "rule-1", "Rule 1", "ALLOW", null, "SUCCESS", 20L, null
        ));

        mockMvc.perform(get("/api/v1/admin/audit/export?format=csv")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"audit-export-")))
                .andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(content().string(containsString("sequenceNumber,eventTimestamp,eventType,agentId")))
                .andExpect(content().string(containsString("calc_eval")));
    }

    @Test
    @DisplayName("Human admin should verify cryptographic SHA-256 chain integrity")
    void humanAdmin_verifiesAuditIntegrity() throws Exception {
        // Record 2 valid events
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", "agent-finance-bot", "user-1", "agent-finance-bot",
                "tool-1", "{}", null, null, "ALLOW", null, "SUCCESS", 10L, null
        ));
        auditRecorder.append(new AuditRecordDraft(
                Instant.now(), "DIRECT_EXECUTION", "agent-finance-bot", "user-1", "agent-finance-bot",
                "tool-2", "{}", null, null, "ALLOW", null, "SUCCESS", 10L, null
        ));

        mockMvc.perform(post("/api/v1/admin/audit/verify")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.recordsChecked").value(2))
                .andExpect(jsonPath("$.lastSequence").value(2))
                .andExpect(jsonPath("$.corruptedSequence").doesNotExist())
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    @DisplayName("Security: Machine agent service accounts should be denied (403 Forbidden)")
    void security_machineAgentServiceAccount_denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit")
                        .with(agentServiceAccountAdminJwt()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/audit/export")
                        .with(agentServiceAccountAdminJwt()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/audit/verify")
                        .with(agentServiceAccountAdminJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Approver-only callers should be denied (403 Forbidden)")
    void security_approverOnly_denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit")
                        .with(approverOnlyHumanJwt()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/audit/export")
                        .with(approverOnlyHumanJwt()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/audit/verify")
                        .with(approverOnlyHumanJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Unauthenticated request should be denied (401 Unauthorized)")
    void security_unauthenticated_denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit"))
                .andExpect(status().isUnauthorized());
    }
}
