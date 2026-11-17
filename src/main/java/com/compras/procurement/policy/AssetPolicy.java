package com.compras.procurement.policy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Section 13: does a delivered item go into the asset register, and who receives it? The rules are data (the
 * policy version's asset table); this evaluates them. Pure.
 */
public final class AssetPolicy {

    /** @param ruleId the rule that decided; reason is shown to the receiver */
    public record Decision(boolean track, String receiver, String reason, String ruleId) {
    }

    private AssetPolicy() {
    }

    public static Map<String, Object> facts(String assetClass, boolean itDevice, BigDecimal unitPrice,
                                            String category) {
        Map<String, Object> f = new LinkedHashMap<>();
        if (assetClass != null) {
            f.put("assetClass", assetClass);
        }
        f.put("itDevice", itDevice);
        f.put("unitPrice", unitPrice == null ? BigDecimal.ZERO : unitPrice);
        if (category != null) {
            f.put("category", category);
        }
        return f;
    }

    public static Decision decide(PolicySet policy, Map<String, Object> facts) {
        for (PolicySet.AssetRule rule : policy.assets()) {
            if (RuleEngine.matches(rule.when(), facts)) {
                return new Decision(rule.tracks(), rule.receiver(), RuleEngine.render(rule.reason(), rule.when(), facts),
                        rule.id());
            }
        }
        throw new IllegalStateException("Policy " + policy.version() + " has no asset rule for this item.");
    }
}
