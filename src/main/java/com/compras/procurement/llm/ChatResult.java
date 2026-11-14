package com.compras.procurement.llm;

import java.util.List;
import java.util.Map;

/** One model step: either text for the user, or tool calls to execute (or both). */
public record ChatResult(String content, List<ToolCall> toolCalls, String reasoningContent,
                         String finishReason, Map<String, Object> usage) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
