package com.agentgate.gateway.resilience;

import com.agentgate.gateway.resilience.circuitbreaker.ToolFailureClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolFailureClassifier Downstream vs Client Error Tests")
class ToolFailureClassifierTest {

    @Test
    @DisplayName("Classifies HTTP 5xx responses as downstream failures")
    void http5xx_isDownstreamFailure() {
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, 500, null));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, 502, null));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, 503, null));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, 504, null));

        HttpServerErrorException exception500 = HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error", HttpHeaders.EMPTY, new byte[0], null);
        assertTrue(ToolFailureClassifier.isDownstreamFailure(exception500, null, exception500.getMessage()));
    }

    @Test
    @DisplayName("Classifies HTTP 4xx client errors as non-downstream failures (does not trip breaker)")
    void http4xx_isNotDownstreamFailure() {
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, 400, "Bad request"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, 401, "Unauthorized"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, 403, "Forbidden"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, 404, "Not Found"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, 422, "Unprocessable"));

        HttpClientErrorException exception400 = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", HttpHeaders.EMPTY, new byte[0], null);
        assertFalse(ToolFailureClassifier.isDownstreamFailure(exception400, null, exception400.getMessage()));

        HttpClientErrorException exception404 = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        assertFalse(ToolFailureClassifier.isDownstreamFailure(exception404, 404, exception404.getMessage()));
    }

    @Test
    @DisplayName("Classifies connection timeouts, socket timeouts, and connection refused as downstream failures")
    void transportExceptions_areDownstreamFailures() {
        // Socket timeout
        ResourceAccessException socketTimeout = new ResourceAccessException("I/O error", new SocketTimeoutException("Read timed out"));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(socketTimeout, null, socketTimeout.getMessage()));

        // Connection refused
        ResourceAccessException connectRefused = new ResourceAccessException("I/O error", new ConnectException("Connection refused"));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(connectRefused, null, connectRefused.getMessage()));

        // JDK 21 HttpClient timeouts
        HttpTimeoutException httpTimeout = new HttpTimeoutException("request timed out");
        assertTrue(ToolFailureClassifier.isDownstreamFailure(httpTimeout, null, httpTimeout.getMessage()));

        HttpConnectTimeoutException httpConnectTimeout = new HttpConnectTimeoutException("connect timed out");
        assertTrue(ToolFailureClassifier.isDownstreamFailure(httpConnectTimeout, null, httpConnectTimeout.getMessage()));
    }

    @Test
    @DisplayName("Classifies fallback error messages correctly")
    void stringFallback_classification() {
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, null, "Downstream tool error: 503 Service Unavailable"));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, null, "Connection refused: localhost:8082"));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, null, "Read timed out after 5000ms"));
        assertTrue(ToolFailureClassifier.isDownstreamFailure(null, null, "DOWNSTREAM_EXECUTION_ERROR: connection reset"));

        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, null, "Downstream tool error: 400 Bad Request"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, null, "Downstream tool error: 404 Not Found"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, null, "SCHEMA_VALIDATION_FAILED: Missing required property"));
        assertFalse(ToolFailureClassifier.isDownstreamFailure(null, null, "POLICY_DENIED: Policy block"));
    }
}
