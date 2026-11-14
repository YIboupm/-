package com.compras.procurement.intake.agent;

import com.compras.procurement.audit.AuditService;
import com.compras.procurement.llm.ToolCall;
import com.compras.procurement.llm.ToolSpec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches the model's tool calls. Any problem (unknown tool, invalid JSON, a tool's validation error)
 * becomes an error result the model reads on its next step, so it can correct itself instead of the
 * program silently changing or dropping what it asked for.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** What the loop needs from one executed call. */
    public record Executed(String output, boolean ok, String trace, ToolResult result) {
    }

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public ToolRegistry(List<AgentTool> all, ObjectMapper objectMapper, AuditService audit) {
        all.forEach(t -> tools.put(t.name(), t));
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(t -> new ToolSpec(t.name(), t.description(), t.parameters())).toList();
    }

    public Executed execute(ToolCall call, ToolContext context) {
        AgentTool tool = tools.get(call.name());
        Executed executed;
        if (tool == null) {
            executed = error(call, "Unknown tool '" + call.name() + "'. Available tools: " + tools.keySet(), List.of());
        } else {
            try {
                JsonNode args = objectMapper.readTree(call.arguments() == null || call.arguments().isBlank()
                        ? "{}" : call.arguments());
                ToolResult result = tool.execute(args, context);
                String output = result.payload() == null ? null : objectMapper.writeValueAsString(result.payload());
                executed = new Executed(output, true, result.trace(), result);
            } catch (JsonProcessingException e) {
                executed = error(call, "The arguments are not valid JSON: " + e.getOriginalMessage()
                        + ". Call the tool again with a valid JSON object.", List.of());
            } catch (ToolException e) {
                executed = error(call, e.getMessage(), e.problems());
            } catch (RuntimeException e) {
                log.warn("Tool {} failed", call.name(), e);
                executed = error(call, "The tool failed: " + e.getMessage(), List.of());
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", call.name());
        payload.put("arguments", call.arguments());
        payload.put("ok", executed.ok());
        payload.put("output", executed.output() == null ? "(waiting for the employee)" : executed.output());
        audit.record("ToolCall", context.conversation().getId(), payload);
        return executed;
    }

    private Executed error(ToolCall call, String message, List<String> problems) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        if (!problems.isEmpty()) {
            body.put("problems", problems);
        }
        body.put("hint", "Fix the problems and call the tool again, or ask the employee if information is missing.");
        String output;
        try {
            output = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            output = "{\"error\":\"" + message.replace("\"", "'") + "\"}";
        }
        String trace = call.name() + " · rejected: " + message
                + (problems.isEmpty() ? "" : " (" + problems.size() + " problem(s))");
        return new Executed(output, false, trace, null);
    }
}
