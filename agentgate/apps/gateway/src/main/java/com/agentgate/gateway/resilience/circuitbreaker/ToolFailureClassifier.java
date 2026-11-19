package com.agentgate.gateway.resilience.circuitbreaker;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Classifier to determine whether an execution error represents a downstream service failure (FR10).
 * <p>
 * Failure counts include:
 * <ul>
 *   <li>Connection timeouts, connection resets, connection refused</li>
 *   <li>Read / socket timeouts</li>
 *   <li>Downstream HTTP 5xx server responses</li>
 *   <li>Transport-level I/O failures</li>
 * </ul>
 * <p>
 * Excluded from failure counts:
 * <ul>
 *   <li>Gateway governance denials (auth, policy, schema, rate-limit, kill-switch)</li>
 *   <li>Downstream HTTP 4xx client errors (e.g. 400 Bad Request, 404 Not Found)</li>
 * </ul>
 */
public final class ToolFailureClassifier {

    private ToolFailureClassifier() {
    }

    /**
     * Determines whether an error is a downstream tool failure that should be recorded by the circuit breaker.
     *
     * @param throwable optional thrown exception
     * @param httpStatusCode optional HTTP status code
     * @param errorMessage optional error message string
     * @return true if error represents downstream failure; false otherwise
     */
    public static boolean isDownstreamFailure(Throwable throwable, Integer httpStatusCode, String errorMessage) {
        // 1. Explicit HTTP status code check
        if (httpStatusCode != null) {
            if (httpStatusCode >= 500 && httpStatusCode <= 599) {
                return true;
            }
            if (httpStatusCode >= 400 && httpStatusCode <= 499) {
                return false;
            }
        }

        // 2. Exception type inspection
        if (throwable != null) {
            if (isDownstreamThrowable(throwable)) {
                return true;
            }
            if (isClientErrorException(throwable)) {
                return false;
            }
        }

        // 3. String-based fallback (e.g. if exception was caught and converted to text)
        if (errorMessage != null) {
            String lower = errorMessage.toLowerCase();

            // Client errors explicitly should not trip the circuit breaker
            if (lower.contains("400 bad request")
                    || lower.contains("401 unauthorized")
                    || lower.contains("403 forbidden")
                    || lower.contains("404 not found")
                    || lower.contains("422 unprocessable")
                    || lower.contains("httpclienterrorexception")) {
                return false;
            }

            // Downstream server error codes and transport timeout indicators
            if (lower.contains("500") || lower.contains("502") || lower.contains("503") || lower.contains("504")
                    || lower.contains("timed out") || lower.contains("timeout")
                    || lower.contains("request cancelled") || lower.contains("cancelled")
                    || lower.contains("connection refused") || lower.contains("connectexception")
                    || lower.contains("connection reset") || lower.contains("broken pipe")
                    || lower.contains("resourceaccessexception") || lower.contains("sockettimeoutexception")
                    || lower.contains("httptimeoutexception") || lower.contains("httpconnecttimeoutexception")
                    || lower.contains("downstream_execution_error")
                    || lower.contains("server error") || lower.contains("internal server error")) {
                return true;
            }
        }

        return false;
    }

    private static boolean isDownstreamThrowable(Throwable t) {
        if (t instanceof HttpServerErrorException) {
            return true;
        }
        if (t instanceof ResourceAccessException
                || t instanceof SocketTimeoutException
                || t instanceof ConnectException
                || t instanceof TimeoutException
                || t instanceof java.util.concurrent.CancellationException
                || t instanceof HttpConnectTimeoutException
                || t instanceof HttpTimeoutException) {
            return true;
        }
        if (t.getCause() != null && t.getCause() != t) {
            return isDownstreamThrowable(t.getCause());
        }
        return false;
    }

    private static boolean isClientErrorException(Throwable t) {
        if (t instanceof HttpClientErrorException) {
            return true;
        }
        if (t.getCause() != null && t.getCause() != t) {
            return isClientErrorException(t.getCause());
        }
        return false;
    }
}
