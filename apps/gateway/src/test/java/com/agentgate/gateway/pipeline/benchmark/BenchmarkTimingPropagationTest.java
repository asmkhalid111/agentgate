package com.agentgate.gateway.pipeline.benchmark;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BenchmarkTimingPropagationTest {

    @BeforeEach
    void setUp() {
        BenchmarkTimingContext.clear();
        BenchmarkTimingContext.setTimingEnabled(true);
    }

    @AfterEach
    void tearDown() {
        BenchmarkTimingContext.clear();
        BenchmarkTimingContext.setTimingEnabled(false);
    }

    @Test
    void testDiagnosticTimingCheckpointsAndDecompositionIdentity() {
        long t0 = 1_000_000_000L; // Servlet entry
        long t1 = 1_015_000_000L; // Security filter exit (15ms auth)
        long t2 = 1_020_000_000L; // MCP context extract (5ms routing)
        long t3 = 1_042_000_000L; // Dynamic callback enter (22ms MCP parse)
        long t4 = 1_044_000_000L; // Pipeline enter (2ms callback prep)

        BenchmarkTimingContext.setGatewayStartNs(t0);
        BenchmarkTimingContext.setSecurityFilterExitNs(t1);
        BenchmarkTimingContext.setMcpContextExtractNs(t2);
        BenchmarkTimingContext.setCallbackEnterNs(t3);
        BenchmarkTimingContext.setPipelineEnterNs(t4);

        // Verify monotonic checkpoints
        assertTrue(t0 <= t1 && t1 <= t2 && t2 <= t3 && t3 <= t4,
                "Timing checkpoints must be monotonically non-decreasing");

        // Compute spans
        long totalIngressNs = t4 - t0;
        long authNs = t1 - t0;
        long routingNs = t2 - t1;
        long mcpParseNs = t3 - t2;
        long callbackPrepNs = t4 - t3;

        BenchmarkTimingContext.setSecurityIngressNs(totalIngressNs);
        BenchmarkTimingContext.setIngressAuthNs(authNs);
        BenchmarkTimingContext.setIngressRoutingNs(routingNs);
        BenchmarkTimingContext.setIngressMcpParseNs(mcpParseNs);
        BenchmarkTimingContext.setIngressCallbackPrepNs(callbackPrepNs);

        // Mathematical decomposition identity: sum(spans) == totalIngressNs
        long sumOfDecomposedSpans = BenchmarkTimingContext.getIngressAuthNs()
                + BenchmarkTimingContext.getIngressRoutingNs()
                + BenchmarkTimingContext.getIngressMcpParseNs()
                + BenchmarkTimingContext.getIngressCallbackPrepNs();

        assertEquals(totalIngressNs, sumOfDecomposedSpans,
                "Sum of decomposed spans must exactly equal total security ingress interval");
        assertEquals(44_000_000L, totalIngressNs);
        assertEquals(15_000_000L, BenchmarkTimingContext.getIngressAuthNs());
        assertEquals(5_000_000L, BenchmarkTimingContext.getIngressRoutingNs());
        assertEquals(22_000_000L, BenchmarkTimingContext.getIngressMcpParseNs());
        assertEquals(2_000_000L, BenchmarkTimingContext.getIngressCallbackPrepNs());
    }

    @Test
    void testTimingContextClearRemovesAllThreadLocals() {
        BenchmarkTimingContext.setGatewayStartNs(100L);
        BenchmarkTimingContext.setSecurityFilterExitNs(200L);
        BenchmarkTimingContext.setMcpContextExtractNs(300L);
        BenchmarkTimingContext.setCallbackEnterNs(400L);
        BenchmarkTimingContext.setPipelineEnterNs(500L);
        BenchmarkTimingContext.setIngressAuthNs(100L);
        BenchmarkTimingContext.setIngressRoutingNs(100L);
        BenchmarkTimingContext.setIngressMcpParseNs(100L);
        BenchmarkTimingContext.setIngressCallbackPrepNs(100L);
        BenchmarkTimingContext.setSecurityIngressNs(400L);

        BenchmarkTimingContext.clear();

        assertNull(BenchmarkTimingContext.getGatewayStartNs());
        assertNull(BenchmarkTimingContext.getSecurityFilterExitNs());
        assertNull(BenchmarkTimingContext.getMcpContextExtractNs());
        assertNull(BenchmarkTimingContext.getCallbackEnterNs());
        assertNull(BenchmarkTimingContext.getPipelineEnterNs());
        assertNull(BenchmarkTimingContext.getIngressAuthNs());
        assertNull(BenchmarkTimingContext.getIngressRoutingNs());
        assertNull(BenchmarkTimingContext.getIngressMcpParseNs());
        assertNull(BenchmarkTimingContext.getIngressCallbackPrepNs());
        assertNull(BenchmarkTimingContext.getSecurityIngressNs());
    }
}
