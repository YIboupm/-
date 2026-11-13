package com.compras.procurement.catalog;

import java.util.List;

/**
 * One field of a category template. Templates are data (seed/categories.json), so adding a
 * field or a category is a configuration change, not a code change.
 *
 * @param defaultFrom where to prefill the value from: "employee.site", "const:false", or null
 */
public record FieldSpec(
        String name,
        String label,
        FieldType type,
        boolean required,
        String description,
        List<String> allowedValues,
        Integer min,
        Integer max,
        String defaultFrom
) {
}
