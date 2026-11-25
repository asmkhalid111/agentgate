package com.agentgate.gateway.pipeline;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Typed destination allowlist properties for downstream MCP server connections (FR1).
 * Prevents SSRF by restricting downstream MCP endpoints to explicitly allowed schemes and hosts.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "agentgate.mcp.downstream")
public class McpDownstreamProperties {

    @NotEmpty
    private Set<String> allowedSchemes = new LinkedHashSet<>(Set.of("http", "https"));

    @NotEmpty
    private Set<String> allowedHosts = new LinkedHashSet<>(Set.of("localhost", "127.0.0.1", "demo-tools"));

    public Set<String> getAllowedSchemes() {
        return allowedSchemes;
    }

    public void setAllowedSchemes(Set<String> allowedSchemes) {
        this.allowedSchemes = allowedSchemes != null ? allowedSchemes : new LinkedHashSet<>();
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts != null ? allowedHosts : new LinkedHashSet<>();
    }
}
