package com.agentgate.demotools.controller;

import com.agentgate.demotools.DemoApiSecurity;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContextManager;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MockOrderWebTest {
    // Bootstrap the MVC slice directly so this module can use the course's JUnit 5 engine.
    @WebMvcTest(controllers = {MockOrderController.class, WebhookReceiverController.class},
                properties = {"agentgate.demo.token=test-fixture", "spring.mvc.problemdetails.enabled=true"})
    @Import(DemoApiSecurity.class)
    static class OrderSlice {}


    @Test
    void retrievesRequestedOrder() throws Exception {
        var context = new TestContextManager(OrderSlice.class).getTestContext().getApplicationContext();
        MockMvc mvc = context.getBean(MockMvc.class);
        mvc.perform(get("/api/orders/demo-1")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(get("/api/orders/demo-1").header("X-AgentGate-Demo-Token", "test-fixture"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("demo-1"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/webhook/approvals")
                        .header("X-AgentGate-Demo-Token", "test-fixture").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
