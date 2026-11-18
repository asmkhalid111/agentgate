package com.agentgate.gateway.policy;

import com.agentgate.gateway.config.TestSecurityConfig;
import com.agentgate.gateway.policy.domain.ConditionOperator;
import com.agentgate.gateway.policy.domain.PolicyAction;
import com.agentgate.gateway.policy.domain.PolicyRule;
import com.agentgate.gateway.policy.domain.StructuredCondition;
import com.agentgate.gateway.policy.repository.PolicyRepository;
import com.agentgate.gateway.policy.service.StructuredPolicyEvaluator;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
public class PolicyPlaygroundControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PolicyRepository policyRepository;

    @Autowired
    private StructuredPolicyEvaluator evaluator;

    @BeforeEach
    void setUp() {
        policyRepository.save(new PolicyRule("pol-small-refund", "Finance Bot Low Value Refund", "agent-finance-*", "orders_refund",
                List.of(new StructuredCondition("amount", ConditionOperator.LTE, 2000)),
                PolicyAction.ALLOW, false, 20, 1, true));
        evaluator.reloadPolicies();
    }

    @Test
    @DisplayName("Playground evaluates live policy for refund under threshold")
    void playground_shouldEvaluateLivePolicy() throws Exception {
        String payload = """
                {
                  "agentId": "agent-finance-bot",
                  "toolName": "orders_refund",
                  "arguments": { "orderId": "ORD-1", "amount": 1000 },
                  "userId": "usr-finance",
                  "riskLevel": "HIGH"
                }
                """;

        mockMvc.perform(post("/api/v1/policies/playground")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.matchedRuleId").value("pol-small-refund"));
    }

    @Test
    @DisplayName("Playground previews draft rule without requiring database persistence")
    void playground_shouldPreviewDraftRule() throws Exception {
        String payload = """
                {
                  "agentId": "agent-draft-bot",
                  "toolName": "draft_tool",
                  "arguments": {},
                  "draftRule": {
                    "id": "draft-rule-1",
                    "name": "Draft Allow Policy",
                    "agentPattern": "agent-draft-*",
                    "toolPattern": "draft_tool",
                    "conditions": [],
                    "action": "ALLOW",
                    "dryRun": false,
                    "priority": 10,
                    "version": 1,
                    "enabled": true
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/policies/playground")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.matchedRuleId").value("draft-rule-1"))
                .andExpect(jsonPath("$.matchedRuleName").value("Draft Allow Policy"));
    }
}
