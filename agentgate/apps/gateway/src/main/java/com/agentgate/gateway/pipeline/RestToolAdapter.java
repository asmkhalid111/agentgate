package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.common.DemoServiceCredential;
import com.agentgate.gateway.common.ToolExecutionContext;
import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;
import com.agentgate.gateway.resilience.config.ResilienceProperties;
import com.agentgate.gateway.pipeline.benchmark.BenchmarkTimingContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RestToolAdapter implements ToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(RestToolAdapter.class);
    private final ObjectMapper objectMapper;
    private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

    private DemoServiceCredential demoCredential = new DemoServiceCredential("", "");

    @Autowired
    void setDemoCredential(DemoServiceCredential demoCredential) {
        this.demoCredential = demoCredential;
    }

    @Autowired(required = false)
    void setObservationRegistry(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }
    private final ResilienceProperties resilienceProperties;
    private final ConcurrentHashMap<String, RestClient> restClients = new ConcurrentHashMap<>();

    @Autowired
    public RestToolAdapter(ObjectMapper objectMapper, @Autowired(required = false) ResilienceProperties resilienceProperties) {
        this.objectMapper = objectMapper;
        this.resilienceProperties = resilienceProperties != null ? resilienceProperties : new ResilienceProperties();
    }

    public RestToolAdapter(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    private RestClient getRestClient(String toolName) {
        return restClients.computeIfAbsent(toolName, name -> {
            Duration connectTimeout = resilienceProperties.getConnectTimeout(name);
            Duration readTimeout = resilienceProperties.getReadTimeout(name);

            HttpClient httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(connectTimeout)
                    .build();

            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(readTimeout);

            return RestClient.builder()
                    .observationRegistry(observationRegistry)
                    .requestFactory(requestFactory)
                    .build();
        });
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
            for (Map.Entry<String, Object> entry : new HashMap<>(remainingArgs).entrySet()) {
                String placeholder = "{" + entry.getKey() + "}";
                if (resolvedUrl.contains(placeholder)) {
                    resolvedUrl = resolvedUrl.replace(placeholder, String.valueOf(entry.getValue()));
                    remainingArgs.remove(entry.getKey());
                }
            }

            HttpMethod method = HttpMethod.valueOf(tool.httpMethod().toUpperCase());
            RestClient restClient = getRestClient(tool.name());
            URI target = URI.create(resolvedUrl);
            RestClient.RequestBodySpec spec = restClient.method(method).uri(target);
            spec.headers(headers -> demoCredential.apply(target, headers::set));

            if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
                spec.contentType(MediaType.APPLICATION_JSON);
                spec.body(remainingArgs);
            }

            long downstreamStartNs = System.nanoTime();
            var response = spec.retrieve().toEntity(String.class);
            String responseBody = response.getBody();
            long downstreamDurationNs = System.nanoTime() - downstreamStartNs;
            if (BenchmarkTimingContext.isTimingEnabled()) {
                BenchmarkTimingContext.setDownstreamDurationNs(downstreamDurationNs);
            }
            long latency = downstreamDurationNs / 1_000_000;

            if (!response.getStatusCode().is2xxSuccessful()) {
                return ToolExecutionResult.failure("Unexpected downstream HTTP status " + response.getStatusCode(),
                        latency, null, response.getStatusCode().value());
            }

            Object parsedData;
            try {
                parsedData = objectMapper.readValue(responseBody, Object.class);
            } catch (Exception e) {
                parsedData = responseBody;
            }

            log.info("Successfully executed REST tool '{}' [latency={}ms]", tool.name(), latency);
            return ToolExecutionResult.success(parsedData, latency);

        } catch (HttpStatusCodeException e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Failed executing REST tool '{}' [status={}]: {}", tool.name(), e.getStatusCode(), e.getMessage());
            return ToolExecutionResult.failure("Downstream tool error: " + e.getMessage(), latency, e, e.getStatusCode().value());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Failed executing REST tool '{}': {}", tool.name(), e.getMessage());
            return ToolExecutionResult.failure("Downstream tool error: " + e.getMessage(), latency, e, null);
        }
    }
}
