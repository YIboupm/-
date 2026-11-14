package com.compras.procurement.intake.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small helpers to write JSON Schemas for tool parameters. */
public final class Schemas {

    private Schemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", properties);
        s.put("required", List.of(required));
        return s;
    }

    public static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> anyValue(String description) {
        return Map.of("description", description);
    }

    public static Map<String, Object> enumOf(String description, String... values) {
        return Map.of("type", "string", "enum", List.of(values), "description", description);
    }

    public static Map<String, Object> array(Map<String, Object> items, String description) {
        return Map.of("type", "array", "items", items, "description", description);
    }

    /** Alternatives the agent looked at and decided not to use, with the reason. */
    public static Map<String, Object> considered() {
        return array(object(props(
                "id", string("Company resource id or catalog SKU."),
                "why_not", string("Why it is not used, in the employee's terms (e.g. needs a portable device).")),
                "id", "why_not"),
                "Company resources or catalog items that also fit but are not used, each with the reason. The "
                        + "system checks the must-haves against all of them and rejects the proposal if a fitting "
                        + "one is missing here.");
    }

    public static Map<String, Object> freeObject(String description) {
        return Map.of("type", "object", "description", description);
    }

    /** The requirement shape shared by check_requirements and the propose_* tools. */
    public static Map<String, Object> requirements() {
        return array(object(props(
                "description", string("The condition in a few plain words, e.g. \"Must run macOS\"."),
                "attribute", string("Attribute key to compare (os, ramGb, gpuType, metalSupported, gpuComputeApi ...). "
                        + "Use null if no attribute covers it."),
                "operator", enumOf("eq: equals; neq: must not be; min: at least; max: at most.",
                        "eq", "neq", "min", "max"),
                "value", anyValue("The value the employee wants, in their meaning (e.g. \"macOS\", 32, true)."),
                "strength", enumOf("must = required by the employee; preference = nice to have.", "must", "preference")),
                "description", "operator", "strength"), "Every condition the employee put on what they need.");
    }
}
