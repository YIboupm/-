package com.compras.procurement.intake.agent;

import com.compras.procurement.matching.Requirement;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared parsing of tool arguments; every problem is collected and reported back to the model. */
public final class ArgParser {

    private static final Set<String> OPERATORS = Set.of("eq", "neq", "min", "max");

    private ArgParser() {
    }

    public static String text(JsonNode args, String name) {
        JsonNode n = args.path(name);
        return n.isMissingNode() || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }

    public static String required(JsonNode args, String name, List<String> problems) {
        String v = text(args, name);
        if (v == null) {
            problems.add("'" + name + "' is required.");
        }
        return v;
    }

    /** Requirements as written by the model, validated for shape only: their meaning is never rewritten. */
    public static List<Requirement> requirements(JsonNode node, List<String> problems) {
        List<Requirement> list = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return list;
        }
        if (!node.isArray()) {
            problems.add("'requirements' must be an array.");
            return list;
        }
        int i = 0;
        for (JsonNode r : node) {
            i++;
            String description = text(r, "description");
            String operator = text(r, "operator");
            String strength = text(r, "strength");
            if (description == null) {
                problems.add("requirements[" + i + "].description is required.");
            }
            if (operator == null || !OPERATORS.contains(operator)) {
                problems.add("requirements[" + i + "].operator must be one of " + OPERATORS + ".");
            }
            if (!"must".equals(strength) && !"preference".equals(strength)) {
                problems.add("requirements[" + i + "].strength must be 'must' or 'preference'.");
            }
            JsonNode v = r.path("value");
            Object value = v.isMissingNode() || v.isNull() ? null
                    : v.isNumber() ? v.numberValue() : v.isBoolean() ? (Object) v.booleanValue() : v.asText();
            if (value == null && ("min".equals(operator) || "max".equals(operator))) {
                problems.add("requirements[" + i + "].value is required for min/max.");
            }
            list.add(new Requirement(description, text(r, "attribute"), operator, value, strength));
        }
        return list;
    }
}
