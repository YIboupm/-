package com.compras.procurement.resolution;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/** Typed reads of a normalised field map (values are String, Integer or Boolean). */
public final class FieldValues {

    private FieldValues() {
    }

    public static Optional<String> string(Map<String, Object> fields, String name) {
        Object v = fields.get(name);
        if (v == null) {
            return Optional.empty();
        }
        String s = v.toString().trim();
        return s.isEmpty() ? Optional.empty() : Optional.of(s);
    }

    public static Optional<Integer> integer(Map<String, Object> fields, String name) {
        Object v = fields.get(name);
        if (v instanceof Number n) {
            return Optional.of(n.intValue());
        }
        try {
            return v == null ? Optional.empty() : Optional.of(Integer.parseInt(v.toString().trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static Optional<LocalDate> date(Map<String, Object> fields, String name) {
        try {
            return string(fields, name).map(LocalDate::parse);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static boolean flag(Map<String, Object> fields, String name) {
        Object v = fields.get(name);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }
}
