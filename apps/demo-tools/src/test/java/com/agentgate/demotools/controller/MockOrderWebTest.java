package com.agentgate.demotools.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContextManager;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MockOrderWebTest {
    // Bootstrap the MVC slice directly so this module can use the course's JUnit 5 engine.
    @WebMvcTest(MockOrderController.class)
    static class OrderSlice {}


    @Test
    void retrievesRequestedOrder() throws Exception {
        var context = new TestContextManager(OrderSlice.class).getTestContext().getApplicationContext();
        MockMvc mvc = context.getBean(MockMvc.class);
        mvc.perform(get("/api/orders/demo-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value("demo-1"));
    }
}
