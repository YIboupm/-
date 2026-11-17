package com.compras.procurement.policy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The vocabulary the rules may use: what the system knows about a request. A policy can combine these freely;
 * a fact that is not here needs code (someone has to collect it) before a rule can use it. Request attributes that
 * forms already collect are also available as {@code attr.<name>}.
 */
public final class Facts {

    public enum Type { MONEY, NUMBER, BOOL, TEXT }

    public record Def(Type type, String description) {
    }

    public static final String CONTRACT_VALUE = "contractValue";
    public static final String AWARD_VALUE = "awardValue";

    public static final Map<String, Def> VOCABULARY = vocabulary();

    private static Map<String, Def> vocabulary() {
        Map<String, Def> m = new LinkedHashMap<>();
        m.put(CONTRACT_VALUE, new Def(Type.MONEY, "Whole-term value in EUR, after the split check (0 until priced)"));
        m.put(AWARD_VALUE, new Def(Type.MONEY, "Value of the quote being awarded (quote rules only)"));
        m.put("nonStandard", new Def(Type.BOOL, "Not in the catalog or a framework agreement (a special request)"));
        m.put("catalogItem", new Def(Type.BOOL, "A standard catalog request that no buyer had to source"));
        m.put("frameworkCategory", new Def(Type.BOOL, "The category is covered by framework agreements (cloud)"));
        m.put("category", new Def(Type.TEXT, "Catalog category code, e.g. LAPTOP, CLOUD"));
        m.put("department", new Def(Type.TEXT, "Department that pays"));
        m.put("overBudget", new Def(Type.BOOL, "The department's remaining quarterly budget does not cover it"));
        m.put("termMonths", new Def(Type.NUMBER, "Length of a service or subscription in months"));
        m.put("soleSource", new Def(Type.BOOL, "Only one supplier can provide it (confirmed by the employee)"));
        m.put("dataClassification", new Def(Type.TEXT, "Public, Internal, Confidential or Restricted"));
        m.put("region", new Def(Type.TEXT, "Where a cloud service runs: Spain, Other EU, Hong Kong"));
        m.put("assetClass", new Def(Type.TEXT, "What a delivered item is (asset rules): LAPTOP, MONITOR, FURNITURE..."));
        m.put("itDevice", new Def(Type.BOOL, "The delivered item is IT equipment (asset rules)"));
        m.put("unitPrice", new Def(Type.MONEY, "Price of one delivered unit in EUR (asset rules)"));
        return java.util.Collections.unmodifiableMap(m);
    }

    private Facts() {
    }

    public static boolean known(String fact) {
        return fact != null && (VOCABULARY.containsKey(fact) || (fact.startsWith("attr.") && fact.length() > 5));
    }

    public static Type type(String fact) {
        Def d = VOCABULARY.get(fact);
        return d == null ? Type.TEXT : d.type();
    }

    public static Map<String, Object> of(boolean nonStandard, boolean catalogItem, boolean frameworkCategory,
                                         BigDecimal contractValue, String category, String department,
                                         boolean overBudget, Map<String, Object> attributes) {
        Map<String, Object> f = new LinkedHashMap<>();
        Map<String, Object> a = attributes == null ? Map.of() : attributes;
        a.forEach((k, v) -> {
            if (v != null) {
                f.put("attr." + k, v);
            }
        });
        for (String k : new String[]{"termMonths", "soleSource", "dataClassification", "region"}) {
            if (a.get(k) != null && !String.valueOf(a.get(k)).isBlank()) {
                f.put(k, a.get(k));
            }
        }
        f.put(CONTRACT_VALUE, contractValue == null ? BigDecimal.ZERO : contractValue);
        f.put("nonStandard", nonStandard);
        f.put("catalogItem", catalogItem);
        f.put("frameworkCategory", frameworkCategory);
        if (category != null) {
            f.put("category", category);
        }
        if (department != null) {
            f.put("department", department);
        }
        f.put("overBudget", overBudget);
        return f;
    }
}
