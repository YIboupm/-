package com.compras.procurement.llm;

import java.util.Map;

/** The model's text plus the provider's token usage (prompt, completion, reasoning ...), kept for the audit log. */
public record LlmResponse(String content, Map<String, Object> usage) {
}
