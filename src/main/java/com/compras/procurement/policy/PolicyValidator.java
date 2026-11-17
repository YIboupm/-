package com.compras.procurement.policy;

import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.policy.PolicySet.Condition;
import com.compras.procurement.policy.PolicySet.StepDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What every policy version must satisfy, whatever its rules say. A policy is data, so anyone allowed to edit it
 * could break the system's guarantees by accident; these checks are the part that is not configurable.
 */
public final class PolicyValidator {

    public static final Set<String> STEP_TYPES = Set.of("APPROVAL", "SOURCING", "STRATEGIC_REVIEW", "BUDGET_RESERVE",
            "FULFIL");
    private static final Set<String> OWNERS = Set.of("BUYER", "CATEGORY_MANAGER");

    private PolicyValidator() {
    }

    public static List<String> problems(PolicySet p) {
        List<String> out = new ArrayList<>();
        if (p.version() == null || !p.version().matches("[A-Za-z0-9._-]{1,40}")) {
            out.add("'version' is required: letters, digits, dot, dash or underscore (e.g. 2026.12).");
        }
        // routes: known building blocks, and the invariants of the money flow
        if (p.routes().isEmpty()) {
            out.add("'routes' is empty: at least one route is needed.");
        }
        Set<String> stages = new HashSet<>();
        p.routes().forEach((name, steps) -> {
            String at = "Route " + name + ": ";
            if (steps == null || steps.isEmpty()) {
                out.add(at + "has no steps.");
                return;
            }
            for (StepDef s : steps) {
                if (s.type() == null || !STEP_TYPES.contains(s.type())) {
                    out.add(at + "unknown step type '" + s.type() + "'. Building blocks: " + STEP_TYPES.stream().sorted().toList());
                } else if ("APPROVAL".equals(s.type())) {
                    if (s.stage() == null || s.stage().isBlank()) {
                        out.add(at + "an APPROVAL step needs a 'stage' (which approval rules apply).");
                    } else {
                        stages.add(s.stage());
                    }
                } else if ("SOURCING".equals(s.type()) && (s.owner() == null || !OWNERS.contains(s.owner()))) {
                    out.add(at + "a SOURCING step needs an 'owner': BUYER or CATEGORY_MANAGER.");
                }
            }
            List<String> types = steps.stream().map(StepDef::type).toList();
            int fulfil = types.indexOf("FULFIL");
            int reserve = types.indexOf("BUDGET_RESERVE");
            if (fulfil != types.size() - 1 || types.lastIndexOf("FULFIL") != fulfil) {
                out.add(at + "must end with exactly one FULFIL step.");
            }
            if (reserve < 0 || reserve != fulfil - 1) {
                out.add(at + "BUDGET_RESERVE must come directly before FULFIL (nothing is ordered without a reservation).");
            }
            if (reserve >= 0 && types.subList(0, reserve).stream().noneMatch("APPROVAL"::equals)) {
                out.add(at + "needs an APPROVAL step before BUDGET_RESERVE (nothing is reserved without the approval rules being applied).");
            }
            if (types.indexOf("STRATEGIC_REVIEW") > 0) {
                out.add(at + "STRATEGIC_REVIEW can only be the first step.");
            }
        });
        if (p.strategicRoute() == null) {
            out.add("One route must start with STRATEGIC_REVIEW: approvers and buyers can always escalate (policy 7).");
        }
        // routing table
        if (p.routing().isEmpty()) {
            out.add("'routing' is empty.");
        } else {
            PolicySet.RoutingRule last = p.routing().get(p.routing().size() - 1);
            if (last.when() != null && !last.when().isEmpty()) {
                out.add("The last routing rule must have no conditions, so every request gets a route.");
            }
        }
        Set<String> ids = new HashSet<>();
        for (PolicySet.RoutingRule r : p.routing()) {
            String at = rule("Routing", r.id(), ids, out);
            conditions(at, r.when(), out);
            if (r.route() == null || !p.routes().containsKey(r.route())) {
                out.add(at + "route '" + r.route() + "' is not defined in 'routes'.");
            }
        }
        // approval tables
        for (PolicySet.ApprovalRule r : p.approvals()) {
            String at = rule("Approval", r.id(), ids, out);
            conditions(at, r.when(), out);
            if (!isRole(r.role())) {
                out.add(at + "unknown role '" + r.role() + "'.");
            }
            if (r.stage() == null || r.stage().isBlank()) {
                out.add(at + "needs a 'stage'.");
            }
            if (r.reason() == null || r.reason().isBlank()) {
                out.add(at + "needs a 'reason': the approver sees why they are asked.");
            }
        }
        for (String stage : stages) {
            if (p.approvals().stream().noneMatch(r -> stage.equals(r.stage()))) {
                out.add("Stage " + stage + " is used by a route but has no approval rule.");
            }
        }
        if (p.approvals().stream().noneMatch(PolicySet.ApprovalRule::budgetDecision)) {
            out.add("One approval rule must have coversOverBudget=true: going over budget needs a knowing decision "
                    + "(policy 8).");
        }
        for (PolicySet.AutoApproval r : p.autoApprovals()) {
            String at = rule("Auto-approval", r.id(), ids, out);
            conditions(at, r.when(), out);
            if (r.when() == null || r.when().stream().noneMatch(c -> "overBudget".equals(c.fact()))) {
                out.add(at + "must require overBudget = false: nothing over budget is approved without a person.");
            }
        }
        for (PolicySet.QuoteRule r : p.quotes()) {
            String at = rule("Quote", r.id(), ids, out);
            conditions(at, r.when(), out);
            if (r.quotes() < 1 || r.quotes() > 10) {
                out.add(at + "'quotes' must be between 1 and 10.");
            }
        }
        // asset rules (section 13)
        if (p.assets().isEmpty()) {
            out.add("'assets' is empty: every delivered item needs a rule saying whether it is registered and who receives it.");
        } else {
            PolicySet.AssetRule last = p.assets().get(p.assets().size() - 1);
            if (last.when() != null && !last.when().isEmpty()) {
                out.add("The last asset rule must have no conditions, so every delivered item has a receiver.");
            }
        }
        for (PolicySet.AssetRule r : p.assets()) {
            String at = rule("Asset", r.id(), ids, out);
            conditions(at, r.when(), out);
            if (!isRole(r.receiver())) {
                out.add(at + "unknown receiver role '" + r.receiver() + "'.");
            }
            if (r.track() == null) {
                out.add(at + "'track' must be true or false.");
            }
        }
        for (PolicySet.PolicyCase c : p.cases()) {
            if (c.name() == null || c.name().isBlank()) {
                out.add("A standard case has no 'name'.");
            }
            for (String fact : c.facts() == null ? java.util.Set.<String>of() : c.facts().keySet()) {
                if (!Facts.VOCABULARY.containsKey(fact)) {
                    out.add("Case '" + c.name() + "': unknown fact '" + fact + "'.");
                }
            }
        }
        if (p.priceDeviationPercent() < 5 || p.priceDeviationPercent() > 500) {
            out.add("parameters.priceDeviationPercent must be between 5 and 500.");
        }
        if (p.splitWindowDays() < 1 || p.splitWindowDays() > 365) {
            out.add("parameters.splitWindowDays must be between 1 and 365.");
        }
        return out;
    }

    private static String rule(String kind, String id, Set<String> ids, List<String> out) {
        if (id == null || id.isBlank()) {
            out.add(kind + " rule without an 'id'.");
        } else if (!ids.add(id)) {
            out.add("Rule id '" + id + "' is used twice.");
        }
        return kind + " rule " + id + ": ";
    }

    private static void conditions(String at, List<Condition> when, List<String> out) {
        for (Condition c : when == null ? List.<Condition>of() : when) {
            if (!Facts.known(c.fact())) {
                out.add(at + "unknown fact '" + c.fact() + "'. Known: " + Facts.VOCABULARY.keySet()
                        + " or attr.<name>.");
            }
            if (c.op() == null || !RuleEngine.OPS.contains(c.op())) {
                out.add(at + "unknown operator '" + c.op() + "'. Operators: " + RuleEngine.OPS.stream().sorted().toList());
            } else if (Set.of("gt", "gte", "lt", "lte").contains(c.op()) && !(c.value() instanceof Number)) {
                out.add(at + "'" + c.op() + "' needs a number for " + c.fact() + ".");
            } else if (Set.of("in", "notIn").contains(c.op()) && !(c.value() instanceof java.util.Collection)) {
                out.add(at + "'" + c.op() + "' needs a list of values for " + c.fact() + ".");
            } else if (Set.of("eq", "ne").contains(c.op()) && c.value() == null) {
                out.add(at + "'" + c.op() + "' needs a value for " + c.fact() + ".");
            }
        }
    }

    private static boolean isRole(String role) {
        try {
            StaffRole.valueOf(String.valueOf(role));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Runs the version's own standard cases. Empty = all pass. */
    public static List<String> failedCases(PolicySet p, PolicyRouter router,
                                           com.compras.procurement.approval.ApprovalPolicy approvals) {
        List<String> out = new ArrayList<>();
        for (PolicySet.PolicyCase c : p.cases()) {
            Map<String, Object> facts = new java.util.LinkedHashMap<>(Facts.of(false, false, false, null, null, null,
                    false, Map.of()));
            if (c.facts() != null) {
                facts.putAll(c.facts());
            }
            try {
                if (c.quotes() != null) {
                    int got = router.quotesRequired(p, RuleEngine.number(facts.get(Facts.AWARD_VALUE)));
                    if (got != c.quotes()) {
                        out.add(c.name() + ": expected " + c.quotes() + " quote(s), the rules give " + got + ".");
                    }
                }
                if (c.tracked() != null || c.receiver() != null) {
                    AssetPolicy.Decision d = AssetPolicy.decide(p, facts);
                    if (c.tracked() != null && c.tracked() != d.track()) {
                        out.add(c.name() + ": expected " + (c.tracked() ? "registered as an asset" : "not registered")
                                + ", the rules give " + (d.track() ? "registered" : "not registered") + " (" + d.ruleId() + ").");
                    }
                    if (c.receiver() != null && !c.receiver().equals(d.receiver())) {
                        out.add(c.name() + ": expected receiver " + c.receiver() + ", the rules give " + d.receiver() + ".");
                    }
                }
                if (c.route() != null) {
                    String got = router.route(p, facts).route();
                    if (!c.route().equals(got)) {
                        out.add(c.name() + ": expected route " + c.route() + ", the rules give " + got + ".");
                    }
                }
                if (c.approvers() != null) {
                    c.approvers().forEach((stage, expected) -> {
                        List<String> got = approvals.roles(p, stage, facts).stream().map(n -> n.role().name()).toList();
                        if (!got.equals(expected)) {
                            out.add(c.name() + ": expected approvers " + expected + " for " + stage
                                    + ", the rules give " + got + ".");
                        }
                    });
                }
            } catch (RuntimeException e) {
                out.add(c.name() + ": " + e.getMessage());
            }
        }
        return out;
    }
}
