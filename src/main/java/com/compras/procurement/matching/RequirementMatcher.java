package com.compras.procurement.matching;

import com.compras.procurement.catalog.CatalogItem;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deterministic comparison of requirements with catalog attributes. Same input, same answer:
 * the model never decides whether "strong GPU" is satisfied, this class does.
 */
@Component
public class RequirementMatcher {

    /**
     * Checks every requirement against the given items (one item for a laptop, several for a kit).
     * A requirement applies to the items that have the attribute; if none has it, the result is UNKNOWN.
     */
    public List<RequirementCheck> check(List<CatalogItem> items, List<Requirement> requirements) {
        List<RequirementCheck> checks = new ArrayList<>();
        if (requirements == null) {
            return checks;
        }
        for (Requirement r : requirements) {
            checks.add(checkOne(items, r));
        }
        return checks;
    }

    /**
     * The candidate that fails the fewest must-haves; ties are broken by fewer failed preferences, then by
     * the lower list price. Only requirements the catalog can check (MET / NOT_MET) count.
     */
    public Optional<CatalogItem> bestMatch(List<CatalogItem> candidates, List<Requirement> requirements) {
        return candidates.stream()
                .min(Comparator.<CatalogItem>comparingLong(c -> failedMusts(c, requirements))
                        .thenComparingLong(c -> failedPreferences(c, requirements))
                        .thenComparing(CatalogItem::listPrice));
    }

    public long failedMusts(CatalogItem item, List<Requirement> requirements) {
        return check(List.of(item), requirements).stream()
                .filter(k -> k.requirement().isMust() && k.status() == RequirementCheck.Status.NOT_MET).count();
    }

    private long failedPreferences(CatalogItem item, List<Requirement> requirements) {
        return check(List.of(item), requirements).stream()
                .filter(k -> !k.requirement().isMust() && k.status() == RequirementCheck.Status.NOT_MET).count();
    }

    private RequirementCheck checkOne(List<CatalogItem> items, Requirement r) {
        if (r.attribute() == null || r.attribute().isBlank()) {
            return new RequirementCheck(r, RequirementCheck.Status.UNKNOWN, null);
        }
        List<String> actuals = new ArrayList<>();
        boolean anyFails = false;
        for (CatalogItem item : items) {
            Object actual = item.attributes() == null ? null : item.attributes().get(r.attribute());
            if (actual == null) {
                continue;
            }
            actuals.add(item.name() + ": " + actual);
            if (!satisfies(actual, r)) {
                anyFails = true;
            }
        }
        if (actuals.isEmpty()) {
            return new RequirementCheck(r, RequirementCheck.Status.UNKNOWN, null);
        }
        String actualText = items.size() == 1 ? actuals.get(0).substring(actuals.get(0).indexOf(": ") + 2)
                : String.join(", ", actuals);
        return new RequirementCheck(r, anyFails ? RequirementCheck.Status.NOT_MET : RequirementCheck.Status.MET,
                actualText);
    }

    static boolean satisfies(Object actual, Requirement r) {
        String op = r.operator() == null ? "eq" : r.operator().toLowerCase(Locale.ROOT);
        if (op.equals("min") || op.equals("max")) {
            BigDecimal a = number(actual);
            BigDecimal wanted = number(r.value());
            if (a == null || wanted == null) {
                return false;
            }
            return op.equals("min") ? a.compareTo(wanted) >= 0 : a.compareTo(wanted) <= 0;
        }
        String a = norm(actual);
        String wanted = norm(r.value());
        return !wanted.isEmpty() && (a.equals(wanted) || a.contains(wanted) || wanted.contains(a));
    }

    private static BigDecimal number(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return new BigDecimal(o.toString().replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String norm(Object o) {
        return o == null ? "" : o.toString().trim().toLowerCase(Locale.ROOT);
    }
}
