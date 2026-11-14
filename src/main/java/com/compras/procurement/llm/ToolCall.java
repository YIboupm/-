package com.compras.procurement.llm;

/** A function call requested by the model; {@code arguments} is the raw JSON string the model wrote. */
public record ToolCall(String id, String name, String arguments) {
}
