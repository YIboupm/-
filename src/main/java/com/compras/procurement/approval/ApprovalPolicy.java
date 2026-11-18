package com.compras.procurement.approval;

import com.compras.procurement.policy.Facts;
import com.compras.procurement.policy.PolicySet;
import com.compras.procurement.policy.PolicySource;
import com.compras.procurement.policy.RuleEngine;
import com.compras.procurement.request.ApprovalStep;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval matrix: which roles must sign off, in which order, and who that is for this request. Which roles is
 * data (the approval rules of the {@link PolicySet}); who that is, and that nobody signs twice or for themselves, is
 * code. Pure (no I/O), so every rule is unit-tested.
 *
 * <p>Segregation of duties (not configurable): nobody approves their own request, and one person never holds two
 * steps of the same decision (the need, or the price); such a step escalates to the next level.
 */
@Component
public class ApprovalPolicy {

    public static final String NEED = "NEED";
    public static final String SPEND = "SPEND";

    /**
     * @param catalogItem   true for standard catalog requests
     * @param overBudget    the department's remaining quarterly budget does not cover the amount
     * @param alreadyDecided steps approved earlier (e.g. the manager approved a special request's need)
     */
    public record Input(String stage, boolean catalogItem, BigDecimal amount, String categoryCode, String department,
                        String requesterName, boolean overBudget, List<ApprovalStep> alreadyDecided,
                        java.util.Map<String, Object> attributes) {

        public Input(String stage, boolean catalogItem, BigDecimal amount, String categoryCode, String department,
                     String requesterName, boolean overBudget, List<ApprovalStep> alreadyDecided) {
            this(stage, catalogItem, amount, categoryCode, department, requesterName, overBudget, alreadyDecided,
                    java.util.Map.of());
        }

        public Map<String, Object> facts() {
            return Facts.of(!catalogItem, catalogItem, false, amount, categoryCode, department, overBudget, attributes);
        }
    }

    /** A role the rules ask for, why, and which rules asked. */
    public record Needed(StaffRole role, String reason, List<String> rules) {
    }

    private final PolicySource policies;

    public ApprovalPolicy(PolicySource policies) {
        this.policies = policies;
    }

    public List<ApprovalStep> steps(Input in, List<StaffMember> staff) {
        return steps(policies.active(), in, staff);
    }

    public List<ApprovalStep> steps(PolicySet policy, Input in, List<StaffMember> staff) {
        List<ApprovalStep> done = in.alreadyDecided() == null ? List.of() : in.alreadyDecided();
        List<ApprovalStep> chain = new ArrayList<>(done);
        for (Needed n : roles(policy, in.stage(), in.facts())) {
            StaffRole role = n.role();
            // approved in this stage already (e.g. the chain is rebuilt after a step was added). An approval of the
            // need is not an approval of the price: the budget holder is asked again once the price is known.
            if (done.stream().anyMatch(s -> s.isApproved() && s.role().equals(role.name())
                    && java.util.Objects.equals(s.stage(), in.stage()))) {
                continue; // e.g. the line manager already approved the need: not asked twice
            }
            StaffMember who = resolve(role, in, chain, staff);
            chain.add(ApprovalStep.pending(who.role().name(), who.id(), who.name(), who.title(),
                    who.role() == role ? n.reason() : n.reason() + " (escalated from " + role.label() + ")",
                    in.stage(), n.rules()));
        }
        return chain;
    }

    /** Did this approved step knowingly accept going over the department's budget? */
    public static boolean coversOverBudget(PolicySet policy, ApprovalStep step) {
        if (!step.isApproved()) {
            return false;
        }
        if (step.rules() == null) { // requests from before the rules were data
            return (step.role().equals(StaffRole.FINANCE_CONTROLLER.name()) || step.role().equals(StaffRole.CFO.name()))
                    && step.reason() != null && step.reason().toLowerCase().contains("budget");
        }
        return step.rules().contains(ApprovalStep.BUDGET_RACE_RULE) || policy.approvals().stream()
                .anyMatch(r -> r.budgetDecision() && step.rules().contains(r.id()));
    }

    /** The roles the rules ask for at this stage, in signing order. Empty: nobody (auto-approved). */
    public List<Needed> roles(PolicySet policy, String stage, Map<String, Object> facts) {
        Map<StaffRole, Needed> needed = new LinkedHashMap<>();
        boolean onlyWaivable = true;
        for (PolicySet.ApprovalRule rule : policy.approvals()) {
            if (!stage.equals(rule.stage()) || !RuleEngine.matches(rule.when(), facts)) {
                continue;
            }
            onlyWaivable &= rule.waivableStep();
            StaffRole role = StaffRole.valueOf(rule.role());
            String reason = RuleEngine.render(rule.reason(), rule.when(), facts);
            needed.merge(role, new Needed(role, reason, List.of(rule.id())), (a, b) -> {
                List<String> ids = new ArrayList<>(a.rules());
                ids.addAll(b.rules());
                return new Needed(role, a.reason() + "; " + b.reason(), ids);
            });
        }
        // Mandatory checks (category, data, amount, budget) take precedence over the small-purchase shortcut.
        boolean auto = onlyWaivable && policy.autoApprovals().stream()
                .anyMatch(a -> stage.equals(a.stage()) && RuleEngine.matches(a.when(), facts));
        return auto ? List.of() : List.copyOf(needed.values());
    }

    /** The person for a role in this department, escalating past anyone who may not decide. */
    public StaffMember resolve(StaffRole role, Input in, List<ApprovalStep> chain, List<StaffMember> staff) {
        StaffRole current = role;
        for (int guard = 0; guard < 5; guard++) {
            StaffRole wanted = current;
            StaffMember candidate = staff.stream()
                    .filter(m -> m.role() == wanted && m.actsFor(in.department()))
                    .filter(m -> !m.name().equalsIgnoreCase(in.requesterName()))
                    // one person holds one step per decision: approving the need and later the price is normal
                    .filter(m -> chain.stream().noneMatch(s -> s.approverId().equals(m.id())
                            && java.util.Objects.equals(s.stage(), in.stage()) && !s.isHistory()))
                    .findFirst().orElse(null);
            if (candidate != null) {
                return candidate;
            }
            if (current == StaffRole.CFO) {
                break;
            }
            current = current.escalation();
        }
        throw new IllegalStateException("No approver available for " + role.label() + " in " + in.department());
    }
}
