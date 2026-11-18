package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.domain.ProtocolType;
import com.agentgate.gateway.registry.domain.RegisteredTool;

public interface ToolAdapter {
    boolean supports(ProtocolType protocolType);
    ToolExecutionResult execute(RegisteredTool tool, ToolExecutionContext context);
}
