package com.agentgate.gateway.registry.event;

import com.agentgate.gateway.registry.domain.RegisteredTool;

public record ToolRegisteredEvent(RegisteredTool tool) {}
