package com.agentgate.demotools.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Simple downstream MCP tool exposed by demo-tools over Streamable HTTP (/mcp) for FR1 interoperability.
 */
@Service
public class DemoMcpInventoryService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DemoMcpInventoryService.class);

    @Tool(name = "inventory_check", description = "Check warehouse inventory availability for a product SKU")
    public Map<String, Object> checkInventory(@ToolParam(description = "Product SKU identifier") String sku) {
        log.info("Handling demo MCP inventory lookup");
        String normalizedSku = (sku != null && !sku.isBlank()) ? sku.trim() : "UNKNOWN";
        return Map.of(
                "sku", normalizedSku,
                "available", true,
                "quantity", 42,
                "warehouse", "DHAKA-HUB-01",
                "checkedAt", Instant.now().toString()
        );
    }
}
