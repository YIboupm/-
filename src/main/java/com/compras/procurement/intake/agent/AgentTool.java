package com.compras.procurement.intake.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * A capability the agent can use. Each tool is a Spring bean; adding a capability (e.g. "check stock in
 * the warehouse") is one new class, with no change to the loop.
 */
public interface AgentTool {

    String name();

    String description();

    /** JSON Schema of the arguments. */
    Map<String, Object> parameters();

    /** Throws {@link ToolException} for invalid input; the message goes back to the model. */
    ToolResult execute(JsonNode args, ToolContext context);
}
