package com.agentgate.gateway.resilience;

import com.agentgate.gateway.resilience.config.ResilienceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResilienceProperties Startup Validation & Tool Overrides Tests")
class ResiliencePropertiesTest {

    @Test
    @DisplayName("Default properties have expected values and pass validation")
    void defaultProperties_passValidation() {
        ResilienceProperties properties = new ResilienceProperties();
        assertDoesNotThrow(properties::validate);

        assertEquals(Duration.ofSeconds(3), properties.getDefaultConnectTimeout());
        assertEquals(Duration.ofSeconds(5), properties.getDefaultReadTimeout());
        assertEquals(Duration.ofSeconds(10), properties.getDefaultExecutionDeadline());
        assertEquals(3, properties.getDefaultFailureThreshold());
        assertEquals(Duration.ofSeconds(30), properties.getDefaultOpenWaitDuration());
    }

    @Test
    @DisplayName("Tool-specific overrides are respected; falls back to defaults when absent")
    void toolOverrides_respected() {
        ResilienceProperties properties = new ResilienceProperties();

        ResilienceProperties.ToolResilienceConfig refundConfig = new ResilienceProperties.ToolResilienceConfig();
        refundConfig.setConnectTimeout(Duration.ofSeconds(1));
        refundConfig.setReadTimeout(Duration.ofSeconds(2));
        refundConfig.setFailureThreshold(5);
        refundConfig.setOpenWaitDuration(Duration.ofSeconds(15));

        properties.setTools(Map.of("orders_refund", refundConfig));
        assertDoesNotThrow(properties::validate);

        // Tool with overrides
        assertEquals(Duration.ofSeconds(1), properties.getConnectTimeout("orders_refund"));
        assertEquals(Duration.ofSeconds(2), properties.getReadTimeout("orders_refund"));
        assertEquals(5, properties.getFailureThreshold("orders_refund"));
        assertEquals(Duration.ofSeconds(15), properties.getOpenWaitDuration("orders_refund"));

        // Tool without overrides (fallback to default)
        assertEquals(Duration.ofSeconds(3), properties.getConnectTimeout("other_tool"));
        assertEquals(Duration.ofSeconds(5), properties.getReadTimeout("other_tool"));
        assertEquals(3, properties.getFailureThreshold("other_tool"));
        assertEquals(Duration.ofSeconds(30), properties.getOpenWaitDuration("other_tool"));
    }

    @Test
    @DisplayName("Validation fails fast on non-positive default values")
    void validationFails_onNonPositiveDefaults() {
        ResilienceProperties prop1 = new ResilienceProperties();
        prop1.setDefaultConnectTimeout(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, prop1::validate);

        ResilienceProperties prop2 = new ResilienceProperties();
        prop2.setDefaultReadTimeout(Duration.ofSeconds(-1));
        assertThrows(IllegalArgumentException.class, prop2::validate);

        ResilienceProperties prop3 = new ResilienceProperties();
        prop3.setDefaultFailureThreshold(0);
        assertThrows(IllegalArgumentException.class, prop3::validate);

        ResilienceProperties prop4 = new ResilienceProperties();
        prop4.setDefaultOpenWaitDuration(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, prop4::validate);
    }
}
