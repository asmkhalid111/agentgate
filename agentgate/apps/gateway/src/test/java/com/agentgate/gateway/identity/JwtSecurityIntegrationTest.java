package com.agentgate.gateway.identity;

import com.agentgate.gateway.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public class JwtSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Actuator health endpoint should be publicly accessible without authentication")
    void actuatorHealth_shouldBePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("MCP endpoint without authentication should be rejected with 401 Unauthorized")
    void mcpEndpoint_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("MCP endpoint with token lacking agent:invoke scope should be rejected with 403 Forbidden")
    void mcpEndpoint_withMissingScope_shouldReturn403() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/list\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("MCP endpoint with valid agent:invoke authority should pass security filter")
    void mcpEndpoint_withAgentInvokeScope_shouldPassSecurity() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test-client\",\"version\":\"1.0\"}}}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_agent:invoke"))
                                .jwt(j -> j.claim("azp", "agent-finance-bot"))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            status != 401 && status != 403, 
                            "Expected security to pass, but got status: " + status
                    );
                });
    }

    @Test
    @DisplayName("Admin route without ROLE_ADMIN authority should be rejected with 403 Forbidden")
    void adminRoute_withoutAdminRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/admin/tools")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_agent:invoke"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API Registry route without ROLE_ADMIN authority should be rejected with 403 Forbidden")
    void apiRegistry_withoutAdminRole_shouldReturn403() throws Exception {
        mockMvc.perform(get("/api/v1/registry/tools")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_agent:invoke"), new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API Registry route with ROLE_ADMIN authority should pass security filter")
    void apiRegistry_withAdminRole_shouldPassSecurity() throws Exception {
        mockMvc.perform(get("/api/v1/registry/tools")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            status != 401 && status != 403,
                            "Expected security to pass, but got status: " + status
                    );
                });
    }

    @Test
    @DisplayName("Policy playground endpoint with ROLE_AGENT authority should pass security filter")
    void policyPlayground_withAgentRole_shouldPassSecurity() throws Exception {
        mockMvc.perform(post("/api/v1/policies/playground")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"test-agent\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    org.junit.jupiter.api.Assertions.assertTrue(
                            status != 401 && status != 403,
                            "Expected security to pass, but got status: " + status
                    );
                });
    }
}
