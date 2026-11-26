package com.agentgate.gateway.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.function.BiConsumer;

@Component
public final class DemoServiceCredential {
    private static final String HEADER = "X-AgentGate-Demo-Token";
    private final URI origin;
    private final String token;

    public DemoServiceCredential(@Value("${agentgate.demo.origin:}") String origin,
                                 @Value("${agentgate.demo.token:}") String token) {
        this.token = token;
        this.origin = origin.isBlank() ? null : URI.create(origin);
        if (this.origin != null && (!isHttp(this.origin) || this.origin.getHost() == null
                || this.origin.getRawUserInfo() != null || this.origin.getRawQuery() != null
                || this.origin.getRawFragment() != null || !this.origin.getRawPath().matches("/?"))) {
            throw new IllegalArgumentException("AgentGate demo origin must be an HTTP(S) origin");
        }
        if ((this.origin == null) != token.isBlank()) {
            throw new IllegalArgumentException("AgentGate demo origin and token must be configured together");
        }
    }

    public void apply(URI target, BiConsumer<String, String> setHeader) {
        if (origin != null && target.getHost() != null && target.getRawUserInfo() == null
                && origin.getScheme().equalsIgnoreCase(target.getScheme())
                && origin.getHost().equalsIgnoreCase(target.getHost())
                && effectivePort(origin) == effectivePort(target)) {
            setHeader.accept(HEADER, token);
        }
    }

    private static boolean isHttp(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
