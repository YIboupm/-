package com.compras.procurement.policy;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sections 4, 5 and 7 of the procurement policy: which route a request takes and how many quotes an award needs.
 * The rules are data ({@link PolicySet}); this class only evaluates them. Pure: same policy version and facts, same
 * answer. Reuse (question 1) is handled before a request exists, by the assistant and the reuse check.
 */
@Component
public class PolicyRouter {

    /**
     * @param nonStandard       not in the catalog / framework (a special request)
     * @param frameworkCategory the category is covered by framework agreements (cloud): a special request in it
     *                          means a provider outside the agreements
     * @param contractValue     whole-term value, after the split check (0 when not priced yet)
     * @param attributes        termMonths, dataClassification, soleSource... from the request
     */
    public record Input(boolean nonStandard, boolean frameworkCategory, BigDecimal contractValue,
                        Map<String, Object> attributes) {
    }

    private final PolicySource policies;

    public PolicyRouter(PolicySource policies) {
        this.policies = policies;
    }

    /** With the policy in force (new requests, the assistant's proposals). */
    public RouteDecision route(Input in) {
        return route(policies.active(), in);
    }

    public RouteDecision route(PolicySet policy, Input in) {
        return route(policy, Facts.of(in.nonStandard(), !in.nonStandard(), in.frameworkCategory(), in.contractValue(),
                null, null, false, in.attributes()));
    }

    public RouteDecision route(PolicySet policy, Map<String, Object> facts) {
        String route = null;
        List<String> reasons = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (PolicySet.RoutingRule rule : policy.routing()) {
            if (!RuleEngine.matches(rule.when(), facts)) {
                continue;
            }
            if (route == null) {
                route = rule.route();
            }
            if (route.equals(rule.route())) {
                ids.add(rule.id());
                String reason = RuleEngine.render(rule.reason(), rule.when(), facts);
                if (!reason.isBlank()) {
                    reasons.add(reason);
                }
            }
        }
        if (route == null) {
            throw new IllegalStateException("Policy " + policy.version() + " has no routing rule for this request.");
        }
        return new RouteDecision(route, reasons, policy.isStrategic(route), ids);
    }

    /** Section 5: how many quotes an award of this value needs. */
    public int quotesRequired(BigDecimal awardValue) {
        return quotesRequired(policies.active(), awardValue);
    }

    public int quotesRequired(PolicySet policy, BigDecimal awardValue) {
        Map<String, Object> facts = Map.of(Facts.AWARD_VALUE, awardValue == null ? BigDecimal.ZERO : awardValue);
        return policy.quotes().stream().filter(q -> RuleEngine.matches(q.when(), facts))
                .map(PolicySet.QuoteRule::quotes).findFirst().orElse(1);
    }

    public static String euros(BigDecimal v) {
        return "€" + String.format("%,.0f", v);
    }
}
