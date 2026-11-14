package com.compras.procurement.llm;

import java.util.Map;

/** A tool offered to the model: name, description and JSON Schema of its arguments. */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
