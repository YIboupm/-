package com.compras.procurement.policy;

import com.compras.procurement.policy.PolicySet.Condition;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evaluates conditions over facts. Pure and tiny on purpose: the rules are data, this is all the code they need. */
public final class RuleEngine {

    public static final Set<String> OPS = Set.of("eq", "ne", "gt", "gte", "lt", "lte", "in", "notIn", "exists",
            "missing");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    private RuleEngine() {
    }

    public static boolean matches(List<Condition> when, Map<String, Object> facts) {
        return when == null || when.stream().allMatch(c -> holds(c, facts));
    }

    static boolean holds(Condition c, Map<String, Object> facts) {
        Object actual = facts.get(c.fact());
        boolean present = actual != null && !String.valueOf(actual).isBlank();
        switch (c.op()) {
            case "exists":
                return present;
            case "missing":
                return !present;
            case "eq":
                return present && same(actual, c.value());
            case "ne":
                return !present || !same(actual, c.value());
            case "in":
                return present && list(c.value()).stream().anyMatch(v -> same(actual, v));
            case "notIn":
                return present && list(c.value()).stream().noneMatch(v -> same(actual, v));
            default:
                BigDecimal a = number(actual);
                BigDecimal b = number(c.value());
                if (a == null || b == null) {
                    return false;
                }
                int cmp = a.compareTo(b);
                return switch (c.op()) {
                    case "gt" -> cmp > 0;
                    case "gte" -> cmp >= 0;
                    case "lt" -> cmp < 0;
                    case "lte" -> cmp <= 0;
                    default -> false;
                };
        }
    }

    /** "{fact}" is replaced by the fact; "{threshold}" by the first numeric value the rule compares against. */
    public static String render(String template, List<Condition> when, Map<String, Object> facts) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value;
            if ("threshold".equals(key)) {
                Condition c = when == null ? null : when.stream().filter(x -> x.value() instanceof Number)
                        .findFirst().orElse(null);
                value = c == null ? "" : format(c.fact(), c.value());
            } else {
                Object v = facts.get(key);
                value = v == null ? "unspecified" : format(key, v);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String format(String fact, Object v) {
        BigDecimal n = v instanceof Number || v instanceof BigDecimal ? number(v) : null;
        if (n != null && Facts.type(fact) == Facts.Type.MONEY) {
            return PolicyRouter.euros(n);
        }
        return n != null ? n.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    private static boolean same(Object a, Object b) {
        if (b == null) {
            return false;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
        }
        BigDecimal x = a instanceof Number ? number(a) : null;
        BigDecimal y = b instanceof Number ? number(b) : null;
        if (x != null && y != null) {
            return x.compareTo(y) == 0;
        }
        return String.valueOf(a).trim().equalsIgnoreCase(String.valueOf(b).trim());
    }

    private static List<?> list(Object v) {
        return v instanceof Collection<?> c ? List.copyOf(c) : v == null ? List.of() : List.of(v);
    }

    static BigDecimal number(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
