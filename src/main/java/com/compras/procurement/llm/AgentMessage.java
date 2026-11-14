package com.compras.procurement.llm;

import java.util.List;

/**
 * One message of an agent conversation in the OpenAI chat format, including tool calls.
 *
 * @param role             "system", "user", "assistant" or "tool"
 * @param toolCalls        tool calls requested by the assistant (assistant messages only)
 * @param toolCallId       the call this message answers (tool messages only)
 * @param reasoningContent the provider's reasoning text, passed back only when thinking mode is on
 */
public record AgentMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId,
                           String reasoningContent) {

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null, null);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage("tool", content, null, toolCallId, null);
    }
}
