package com.agentgate.gateway.killswitch.controller;

import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.killswitch.domain.KillSwitch;
import com.agentgate.gateway.killswitch.domain.KillSwitchType;
import com.agentgate.gateway.killswitch.dto.KillSwitchRequest;
import com.agentgate.gateway.killswitch.repository.KillSwitchRepository;
import com.agentgate.gateway.killswitch.service.KillSwitchService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Emergency Kill Switch Admin REST API Tests (FR8)")
class KillSwitchAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KillSwitchRepository killSwitchRepository;

    @Autowired
    private KillSwitchService killSwitchService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String HUMAN_ADMIN_USERNAME = "admin.security";

    @BeforeEach
    void setUp() {
        killSwitchRepository.deleteAll();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor humanAdminJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                .jwt(j -> j
                        .subject("usr-admin-uuid-001")
                        .claim("preferred_username", HUMAN_ADMIN_USERNAME)
                        .claim("email", "admin.security@agentgate.dev")
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

    private org.springframework.test.web.servlet.request.RequestPostProcessor normalUserHumanJwt() {
        return jwt()
                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                .jwt(j -> j
                        .subject("usr-user-uuid-003")
                        .claim("preferred_username", "standard.user")
                        .claim("email", "user@agentgate.dev")
                        .claim("realm_access", Map.of("roles", List.of("USER")))
                );
    }

    @Test
    @DisplayName("Human admin should activate AGENT kill switch successfully")
    void humanAdmin_activatesAgentKillSwitch_success() throws Exception {
        KillSwitchRequest request = new KillSwitchRequest(
                KillSwitchType.AGENT,
                "agent-compromised-bot",
                "Compromised credentials detected"
        );

        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(humanAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("AGENT"))
                .andExpect(jsonPath("$.target").value("agent-compromised-bot"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.reason").value("Compromised credentials detected"))
                .andExpect(jsonPath("$.createdBy").value(HUMAN_ADMIN_USERNAME));

        assertTrue(killSwitchRepository.findActiveSwitch("agent-compromised-bot", "any-tool", false).isPresent());
    }

    @Test
    @DisplayName("Human admin should activate TOOL kill switch successfully")
    void humanAdmin_activatesToolKillSwitch_success() throws Exception {
        KillSwitchRequest request = new KillSwitchRequest(
                KillSwitchType.TOOL,
                "delete_database",
                "Tool vulnerability identified"
        );

        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(humanAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TOOL"))
                .andExpect(jsonPath("$.target").value("delete_database"))
                .andExpect(jsonPath("$.active").value(true));

        assertTrue(killSwitchRepository.findActiveSwitch("agent-1", "delete_database", false).isPresent());
    }

    @Test
    @DisplayName("Human admin should activate GLOBAL_WRITE kill switch with default GLOBAL target")
    void humanAdmin_activatesGlobalWriteKillSwitch_success() throws Exception {
        KillSwitchRequest request = new KillSwitchRequest(
                KillSwitchType.GLOBAL_WRITE,
                null,
                "Active ransomware alert across downstream services"
        );

        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(humanAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GLOBAL_WRITE"))
                .andExpect(jsonPath("$.target").value("GLOBAL"))
                .andExpect(jsonPath("$.active").value(true));

        // When isWrite = true, should match
        assertTrue(killSwitchRepository.findActiveSwitch("any-agent", "any-tool", true).isPresent());
        // When isWrite = false, should NOT match
        assertFalse(killSwitchRepository.findActiveSwitch("any-agent", "any-tool", false).isPresent());
    }

    @Test
    @DisplayName("Human admin should deactivate kill switch idempotently")
    void humanAdmin_deactivatesKillSwitch_success() throws Exception {
        killSwitchService.activate(KillSwitchType.AGENT, "agent-to-stop", "Test", HUMAN_ADMIN_USERNAME);

        mockMvc.perform(delete("/api/v1/admin/kill-switches/AGENT/agent-to-stop")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertFalse(killSwitchRepository.findActiveSwitch("agent-to-stop", "any-tool", false).isPresent());

        // Deactivating again is idempotent
        mockMvc.perform(delete("/api/v1/admin/kill-switches/AGENT/agent-to-stop")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("Human admin should deactivate GLOBAL_WRITE switch without target in path")
    void humanAdmin_deactivatesGlobalWriteWithoutTarget_success() throws Exception {
        killSwitchService.activate(KillSwitchType.GLOBAL_WRITE, null, "Test", HUMAN_ADMIN_USERNAME);

        mockMvc.perform(delete("/api/v1/admin/kill-switches/GLOBAL_WRITE")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertFalse(killSwitchRepository.findActiveSwitch("any-agent", "any-tool", true).isPresent());
    }

    @Test
    @DisplayName("Human admin should list and paginate kill switches")
    void humanAdmin_listsAndPaginatesKillSwitches() throws Exception {
        killSwitchService.activate(KillSwitchType.AGENT, "agent-1", "Reason 1", HUMAN_ADMIN_USERNAME);
        killSwitchService.activate(KillSwitchType.TOOL, "tool-1", "Reason 2", HUMAN_ADMIN_USERNAME);
        killSwitchService.activate(KillSwitchType.GLOBAL_WRITE, "GLOBAL", "Reason 3", HUMAN_ADMIN_USERNAME);
        killSwitchService.deactivate(KillSwitchType.TOOL, "tool-1", HUMAN_ADMIN_USERNAME);

        // List active only
        mockMvc.perform(get("/api/v1/admin/kill-switches?active=true")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(2));

        // List all
        mockMvc.perform(get("/api/v1/admin/kill-switches")
                        .with(humanAdminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    @DisplayName("Security: Machine agent service accounts should be denied (403 Forbidden)")
    void security_machineAgentServiceAccount_denied() throws Exception {
        KillSwitchRequest request = new KillSwitchRequest(
                KillSwitchType.AGENT,
                "agent-compromised-bot",
                "Machine attempt"
        );

        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(agentServiceAccountAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Approver-only and User-only callers should be denied (403 Forbidden)")
    void security_approverAndUser_denied() throws Exception {
        KillSwitchRequest request = new KillSwitchRequest(
                KillSwitchType.AGENT,
                "agent-compromised-bot",
                "Unauthorized attempt"
        );

        // APPROVER only lacks ROLE_ADMIN
        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(approverOnlyHumanJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        // USER only lacks ROLE_ADMIN
        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(normalUserHumanJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: Unauthenticated request should be denied (401 Unauthorized)")
    void security_unauthenticated_denied() throws Exception {
        mockMvc.perform(get("/api/v1/admin/kill-switches"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Validation: Missing reason or type should return 400 Bad Request")
    void validation_missingFields_badRequest() throws Exception {
        String invalidJson = "{\"type\": null, \"reason\": \"\"}";

        mockMvc.perform(post("/api/v1/admin/kill-switches")
                        .with(humanAdminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
