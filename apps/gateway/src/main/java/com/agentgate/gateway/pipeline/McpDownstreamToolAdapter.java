package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.pipeline.benchmark.BenchmarkTimingContext;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.resilience.config.ResilienceProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Adapter for executing tools on real downstream MCP servers via Streamable HTTP (FR1).
 * Enforces scheme/host allowlist validation, resilience timeouts, and never forwards caller credentials.
 */
@Component
public class McpDownstreamToolAdapter implements ToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpDownstreamToolAdapter.class);

    private final ObjectMapper objectMapper;
    private final ResilienceProperties resilienceProperties;
    private final McpDownstreamProperties downstreamProperties;
    private final ConcurrentHashMap<String, McpSyncClient> clientCache = new ConcurrentHashMap<>();

    @Autowired
    public McpDownstreamToolAdapter(
            ObjectMapper objectMapper,
            @Autowired(required = false) ResilienceProperties resilienceProperties,
            @Autowired(required = false) McpDownstreamProperties downstreamProperties) {
        this.objectMapper = objectMapper;
        this.resilienceProperties = resilienceProperties != null ? resilienceProperties : new ResilienceProperties();
        this.downstreamProperties = downstreamProperties != null ? downstreamProperties : new McpDownstreamProperties();
    }

    public McpDownstreamToolAdapter(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    @Override
    public boolean supports(ProtocolType protocolType) {
        return protocolType == ProtocolType.MCP;
    }

    @Override
    public ToolExecutionResult execute(RegisteredTool tool, ToolExecutionContext context) {
        long startTime = System.currentTimeMillis();
        String cacheKey = null;
        try {
            URI endpointUri = validateAndParseEndpoint(tool.endpointUrl());
            String baseUrl = buildBaseUrl(endpointUri);
            String endpointPath = (endpointUri.getRawPath() != null && !endpointUri.getRawPath().isBlank())
                    ? endpointUri.getRawPath()
                    : "/mcp";

            cacheKey = tool.name() + "|" + baseUrl + endpointPath;
            McpSyncClient client = getOrCreateClient(cacheKey, tool.name(), baseUrl, endpointPath);

            Map<String, Object> args = context.arguments() != null ? context.arguments() : Collections.emptyMap();
            McpSchema.CallToolRequest request = new McpSchema.CallToolRequest(tool.name(), args);

            long downstreamStartNs = System.nanoTime();
            McpSchema.CallToolResult result = client.callTool(request);
            long downstreamDurationNs = System.nanoTime() - downstreamStartNs;
            if (BenchmarkTimingContext.isTimingEnabled()) {
                BenchmarkTimingContext.setDownstreamDurationNs(downstreamDurationNs);
            }
            long latencyMs = downstreamDurationNs / 1_000_000;

            if (result == null) {
                return ToolExecutionResult.failure("Downstream MCP error: null response", latencyMs);
            }

            Object parsedPayload = extractResultPayload(result);
            if (Boolean.TRUE.equals(result.isError())) {
                log.warn("Downstream MCP tool '{}' returned error result [latency={}ms]", tool.name(), latencyMs);
                return ToolExecutionResult.failure("Downstream MCP error: " + parsedPayload, latencyMs);
            }

            log.info("Successfully executed downstream MCP tool '{}' [latency={}ms]", tool.name(), latencyMs);
            return ToolExecutionResult.success(parsedPayload, latencyMs);

        } catch (IllegalArgumentException e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("Rejected invalid downstream MCP endpoint for '{}': {}", tool != null ? tool.name() : "unknown", e.getMessage());
            return ToolExecutionResult.failure("Downstream MCP endpoint error: " + e.getMessage(), latencyMs, e, null);
        } catch (Exception e) {
            if (cacheKey != null) {
                evictClient(cacheKey);
            }
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("Failed executing downstream MCP tool '{}': {}", tool != null ? tool.name() : "unknown", e.getMessage());
            return ToolExecutionResult.failure("Downstream MCP error: " + e.getMessage(), latencyMs, e, null);
        }
    }

    private URI validateAndParseEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint URL must not be blank");
        }
        URI uri = URI.create(endpointUrl.trim());
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("MCP endpoint URL must not contain userinfo credentials");
        }
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!downstreamProperties.getAllowedSchemes().contains(scheme)) {
            throw new IllegalArgumentException("Disallowed MCP endpoint scheme: " + scheme);
        }
        String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
        boolean hostAllowed = downstreamProperties.getAllowedHosts().stream()
                .anyMatch(allowed -> allowed != null && allowed.equalsIgnoreCase(host));
        if (host.isBlank() || !hostAllowed) {
            throw new IllegalArgumentException("Disallowed MCP endpoint host: " + host);
        }
        return uri;
    }

    private String buildBaseUrl(URI uri) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() != -1) {
            sb.append(":").append(uri.getPort());
        }
        return sb.toString();
    }

    private McpSyncClient getOrCreateClient(String cacheKey, String toolName, String baseUrl, String endpointPath) {
        return clientCache.computeIfAbsent(cacheKey, k -> {
            Duration connectTimeout = resilienceProperties.getConnectTimeout(toolName);
            Duration readTimeout = resilienceProperties.getReadTimeout(toolName);

            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                    .endpoint(endpointPath)
                    .connectTimeout(connectTimeout)
                    .build();

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(readTimeout)
                    .build();
            client.initialize();
            log.info("Initialized downstream MCP client for '{}' at {}{}", toolName, baseUrl, endpointPath);
            return client;
        });
    }

    private void evictClient(String cacheKey) {
        McpSyncClient removed = clientCache.remove(cacheKey);
        if (removed != null) {
            try {
                removed.closeGracefully();
            } catch (Exception ignored) {
            }
        }
    }

    private Object extractResultPayload(McpSchema.CallToolResult result) {
        if (result.structuredContent() != null) {
            return result.structuredContent();
        }
        if (result.content() == null || result.content().isEmpty()) {
            return Map.of();
        }
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
        if (text.isBlank()) {
            return result.content().toString();
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    @PreDestroy
    public void shutdown() {
        for (McpSyncClient client : clientCache.values()) {
            try {
                client.closeGracefully();
            } catch (Exception ignored) {
            }
        }
        clientCache.clear();
    }
}
