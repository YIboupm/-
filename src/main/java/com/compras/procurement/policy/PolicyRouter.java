package com.compras.procurement.policy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Section 4 and 7 of the procurement policy as code. Pure: same input, same route, every rule unit-tested.
 * Reuse (question 1) is handled before a request exists, by the assistant and the reuse check.
 */
@Component
public class PolicyRouter {

    /**
     * @param nonStandard       not in the catalog / framework (a special request)
     * @param frameworkCategory the category is covered by framework agreements (cloud): a special request in it
     *                          means a provider outside the agreements
     * @param contractValue     whole-term value, after the split check (0 when not priced yet)
     * @param attributes        termMonths, dataClassification, soleSource from the request
     */
    public record Input(boolean nonStandard, boolean frameworkCategory, BigDecimal contractValue,
                        Map<String, Object> attributes) {
    }

    private final PolicyProperties policy;

    public PolicyRouter(PolicyProperties policy) {
        this.policy = policy;
    }

    public PolicyProperties policy() {
        return policy;
    }

    public RouteDecision route(Input in) {
        List<String> reasons = new ArrayList<>();
        BigDecimal value = in.contractValue() == null ? BigDecimal.ZERO : in.contractValue();
        Map<String, Object> a = in.attributes() == null ? Map.of() : in.attributes();
        if (value.compareTo(policy.strategicAbove()) > 0) {
            reasons.add("Contract value " + euros(value) + " is over " + euros(policy.strategicAbove())
                    + " (policy 7)");
        }
        if (in.nonStandard()) {
            if (in.frameworkCategory()) {
                reasons.add("A supplier outside the framework agreements for this category (policy 7)");
            }
            int term = integer(a.get("termMonths"));
            if (term > policy.maxTermMonths()) {
                reasons.add("A new supplier or service for " + term + " months, over " + policy.maxTermMonths()
                        + " (policy 7)");
            }
            if (truthy(a.get("soleSource"))) {
                reasons.add("Sole source: only one supplier can provide it (policy 7, 10)");
            }
            if ("Restricted".equalsIgnoreCase(String.valueOf(a.get("dataClassification")))) {
                reasons.add("A new supplier would handle Restricted data (policy 7, 9)");
            }
        }
        if (!reasons.isEmpty()) {
            return new RouteDecision(Route.STRATEGIC, reasons);
        }
        return new RouteDecision(in.nonStandard() ? Route.BUYER_SOURCED : Route.STANDARD, List.of());
    }

    /** Section 5: how many quotes an award of this value needs. */
    public int quotesRequired(BigDecimal awardValue) {
        return awardValue != null && awardValue.compareTo(policy.singleQuoteUpTo()) > 0 ? 3 : 1;
    }

    public static String euros(BigDecimal v) {
        return "€" + String.format("%,.0f", v);
    }

    private static int integer(Object v) {
        try {
            return v == null ? 0 : (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean truthy(Object v) {
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v));
    }
}
