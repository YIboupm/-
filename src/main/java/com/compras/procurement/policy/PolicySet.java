package com.compras.procurement.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One version of the procurement policy as data: decision tables (which route, who approves, how many quotes) and
 * the routes themselves, each an ordered list of step building blocks. Changing the policy = publishing a new
 * version of this document; no code changes. Every request records the version it was judged under.
 *
 * <p>What is NOT here, on purpose: segregation of duties, budget secrecy, the budget reservation itself and the
 * "talk to the employee before a strategic route" gate. Those are guarantees of the system, not policy choices.
 */
@Document("policy_versions")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PolicySet(
        @Id String version,
        /** DRAFT, ACTIVE or RETIRED. */
        String status,
        String effectiveFrom,
        String note,
        String basedOn,
        String publishedBy,
        String publishedAt,
        Map<String, Object> parameters,
        List<RoutingRule> routing,
        List<ApprovalRule> approvals,
        List<AutoApproval> autoApprovals,
        List<QuoteRule> quotes,
        Map<String, List<StepDef>> routes,
        List<PolicyCase> cases,
        /** Section 13: which received goods are registered as assets, and who receives them. */
        List<AssetRule> assets
) {
    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    /** fact, op (eq, ne, gt, gte, lt, lte, in, notIn, exists, missing), value. All conditions of a rule must hold. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(String fact, String op, Object value) {
    }

    /** The first matching rule decides the route; every matching rule of that route contributes its reason. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoutingRule(String id, String section, List<Condition> when, String route, String reason) {
    }

    /**
     * Adds an approver role to the chain of a stage. Rules are read top to bottom: that is the signing order.
     *
     * @param waivable         a small-purchase auto-approval may drop this step
     * @param coversOverBudget approving this step is the knowing decision to go over the department's budget
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApprovalRule(String id, String section, String stage, List<Condition> when, String role,
                               String reason, Boolean waivable, Boolean coversOverBudget) {
        public boolean waivableStep() {
            return Boolean.TRUE.equals(waivable);
        }

        public boolean budgetDecision() {
            return Boolean.TRUE.equals(coversOverBudget);
        }
    }

    /** When it matches and only waivable approvers would be needed, nobody has to approve. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AutoApproval(String id, String section, String stage, List<Condition> when, String reason) {
    }

    /** The first matching rule gives the number of quotes an award needs; none matching means one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuoteRule(String id, String section, List<Condition> when, int quotes) {
    }

    /**
     * One building block of a route. Types (implemented in code): APPROVAL (with a stage), SOURCING (with an owner),
     * STRATEGIC_REVIEW, BUDGET_RESERVE, FULFIL.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StepDef(String type, String stage, String owner, String label) {
    }

    /**
     * Section 13: the first matching rule decides whether delivered goods are registered as assets ({@code track})
     * and which role receives them. Facts: assetClass, itDevice, unitPrice, category.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AssetRule(String id, String section, List<Condition> when, Boolean track, String receiver,
                            String reason) {
        public boolean tracks() {
            return Boolean.TRUE.equals(track);
        }
    }

    /** A standard case every version must get right before it can be published. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PolicyCase(String name, Map<String, Object> facts, String route, Map<String, List<String>> approvers,
                             Integer quotes, Boolean tracked, String receiver) {
    }

    public List<RoutingRule> routing() { return routing == null ? List.of() : routing; }
    public List<ApprovalRule> approvals() { return approvals == null ? List.of() : approvals; }
    public List<AutoApproval> autoApprovals() { return autoApprovals == null ? List.of() : autoApprovals; }
    public List<QuoteRule> quotes() { return quotes == null ? List.of() : quotes; }
    public Map<String, List<StepDef>> routes() { return routes == null ? Map.of() : routes; }
    public List<PolicyCase> cases() { return cases == null ? List.of() : cases; }
    public List<AssetRule> assets() { return assets == null ? List.of() : assets; }
    public Map<String, Object> parameters() { return parameters == null ? Map.of() : parameters; }

    public int splitWindowDays() {
        Object v = parameters().get("splitWindowDays");
        try {
            return v == null ? 30 : (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /** Policy 5: how far (percent) an awarded unit price may be from the line's reference price without a note. */
    public int priceDeviationPercent() {
        Object v = parameters().get("priceDeviationPercent");
        try {
            return v == null ? 40 : (int) Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 40;
        }
    }

    public List<StepDef> steps(String route) {
        List<StepDef> steps = routes().get(route);
        return steps == null ? List.of() : steps;
    }

    /** A route is strategic when it starts with the category manager's review. */
    public boolean isStrategic(String route) {
        List<StepDef> steps = steps(route);
        return !steps.isEmpty() && "STRATEGIC_REVIEW".equals(steps.get(0).type());
    }

    /** The route a manual escalation goes to. */
    public String strategicRoute() {
        return routes().keySet().stream().filter(this::isStrategic).findFirst().orElse(null);
    }

    /** Every contract-value threshold any rule uses: the split check (policy 2) watches these. */
    public Set<BigDecimal> valueThresholds() {
        Set<BigDecimal> out = new LinkedHashSet<>();
        java.util.stream.Stream.of(
                routing().stream().map(RoutingRule::when),
                approvals().stream().map(ApprovalRule::when),
                autoApprovals().stream().map(AutoApproval::when)).flatMap(s -> s)
                .filter(java.util.Objects::nonNull).flatMap(List::stream)
                .filter(c -> Facts.CONTRACT_VALUE.equals(c.fact()) && c.value() instanceof Number)
                .forEach(c -> out.add(new BigDecimal(String.valueOf(c.value()))));
        return out;
    }

    public PolicySet withStatus(String status, String by, String at) {
        return new PolicySet(version, status, effectiveFrom, note, basedOn, by == null ? publishedBy : by,
                at == null ? publishedAt : at, parameters, routing, approvals, autoApprovals, quotes, routes, cases, assets);
    }

    public PolicySet asDraft(String newVersion, String basedOnVersion, String newNote) {
        return new PolicySet(newVersion, DRAFT, effectiveFrom, newNote == null ? note : newNote, basedOnVersion, null,
                null, parameters, routing, approvals, autoApprovals, quotes, routes, cases, assets);
    }

    /** The same version with the asset rules a version from before section 13 did not have. */
    public PolicySet withAssets(List<AssetRule> rules) {
        return new PolicySet(version, status, effectiveFrom, note, basedOn, publishedBy, publishedAt, parameters,
                routing, approvals, autoApprovals, quotes, routes, cases, rules);
    }
}
