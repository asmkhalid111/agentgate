package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Component
public class RestToolAdapter implements ToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(RestToolAdapter.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestToolAdapter(ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ProtocolType protocolType) {
        return protocolType == ProtocolType.REST;
    }

    @Override
    public ToolExecutionResult execute(RegisteredTool tool, ToolExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String resolvedUrl = tool.endpointUrl();
            Map<String, Object> remainingArgs = new HashMap<>(context.arguments() != null ? context.arguments() : Map.of());

            // Replace path variables in endpoint URL, e.g. {orderId}
            for (Map.Entry<String, Object> entry : context.arguments().entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (resolvedUrl.contains(placeholder)) {
                    resolvedUrl = resolvedUrl.replace(placeholder, String.valueOf(entry.getValue()));
                    remainingArgs.remove(entry.getKey());
                }
            }

            HttpMethod method = HttpMethod.valueOf(tool.httpMethod().toUpperCase());
            RestClient.RequestBodySpec spec = restClient.method(method).uri(URI.create(resolvedUrl));

            if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
                spec.contentType(MediaType.APPLICATION_JSON);
                spec.body(remainingArgs);
            }

            String responseBody = spec.retrieve().body(String.class);
            long latency = System.currentTimeMillis() - startTime;

            Object parsedData;
            try {
                parsedData = objectMapper.readValue(responseBody, Object.class);
            } catch (Exception e) {
                parsedData = responseBody;
            }

            log.info("Successfully executed REST tool '{}' [latency={}ms]", tool.name(), latency);
            return ToolExecutionResult.success(parsedData, latency);

        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Failed executing REST tool '{}': {}", tool.name(), e.getMessage());
            return ToolExecutionResult.failure("Downstream tool error: " + e.getMessage(), latency);
        }
    }
}
