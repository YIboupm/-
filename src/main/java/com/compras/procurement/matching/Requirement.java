package com.compras.procurement.matching;

/**
 * One condition the employee put on what they need, as the model parsed it from their words.
 * The model only parses; whether a catalog item meets the condition is decided by
 * {@link RequirementMatcher}.
 *
 * @param description the condition in plain words, e.g. "Must run macOS"
 * @param attribute   a catalog attribute key (os, ramGb, gpuType ...) or null when no attribute covers it
 * @param operator    "eq", "min" or "max"
 * @param value       the value asked for, e.g. "macOS", 32, "dedicated"
 * @param strength    "must" or "preference"
 */
public record Requirement(String description, String attribute, String operator, Object value, String strength) {

    public boolean isMust() {
        return !"preference".equalsIgnoreCase(strength);
    }
}
