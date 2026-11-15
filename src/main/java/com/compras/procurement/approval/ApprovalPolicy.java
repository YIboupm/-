package com.compras.procurement.approval;

import com.compras.procurement.request.ApprovalStep;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The approval matrix as code: which roles must sign off, in which order, and who that is for this request.
 * Pure (no I/O), so every rule is unit-tested.
 *
 * <p>Segregation of duties: nobody approves their own request, and one person never holds two steps of the
 * same request; such a step escalates to the next level.
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

        String attribute(String name) {
            Object v = attributes == null ? null : attributes.get(name);
            return v == null ? "" : String.valueOf(v);
        }
    }

    private final ApprovalProperties matrix;

    public ApprovalPolicy(ApprovalProperties matrix) {
        this.matrix = matrix;
    }

    public List<ApprovalStep> steps(Input in, List<StaffMember> staff) {
        Map<StaffRole, String> needed = new LinkedHashMap<>(); // role -> reason, in decision order
        if (NEED.equals(in.stage())) {
            needed.put(StaffRole.LINE_MANAGER, "Approve the need before a buyer sources and prices it");
        } else {
            BigDecimal amount = in.amount() == null ? BigDecimal.ZERO : in.amount();
            boolean cloud = in.categoryCode() != null && matrix.cloudCategories().contains(in.categoryCode());
            // cloud is never touchless: the platform team has to set up the account anyway
            boolean auto = in.catalogItem() && !cloud && !in.overBudget()
                    && amount.compareTo(matrix.autoApproveUpTo()) <= 0;
            if (auto) {
                return List.of();
            }
            needed.put(StaffRole.LINE_MANAGER, "Budget holder for " + in.department());
            if (in.categoryCode() != null && matrix.itCategories().contains(in.categoryCode())) {
                needed.put(StaffRole.IT_ASSET_MANAGER, "IT equipment: compatibility, security and asset register");
            }
            if (in.categoryCode() != null && matrix.cloudCategories().contains(in.categoryCode())) {
                needed.put(StaffRole.CLOUD_PLATFORM, "Cloud: account set-up in the landing zone, sizing and cost alerts");
                String data = in.attribute("dataClassification");
                String region = in.attribute("region");
                boolean sensitive = data.equals("Confidential") || data.equals("Restricted");
                boolean outsideEu = !region.isEmpty() && !region.equals("Spain") && !region.equals("Other EU");
                if (sensitive || (outsideEu && !data.equals("Public"))) {
                    needed.put(StaffRole.IT_SECURITY, "Data protection: " + (data.isEmpty() ? "unclassified" : data)
                            + " data" + (outsideEu ? " outside the EU (" + region + ")" : ""));
                }
            }
            if (amount.compareTo(matrix.managerUpTo()) > 0) {
                needed.put(StaffRole.DEPARTMENT_HEAD, "Over " + euros(matrix.managerUpTo()));
                needed.put(StaffRole.FINANCE_CONTROLLER, "Over " + euros(matrix.managerUpTo()));
            }
            if (in.overBudget()) {
                needed.merge(StaffRole.FINANCE_CONTROLLER, "Over the department's quarterly budget",
                        (a, b) -> a + "; " + b);
            }
            if (amount.compareTo(matrix.cfoAbove()) > 0) {
                needed.put(StaffRole.CFO, "Over " + euros(matrix.cfoAbove()));
            }
        }

        List<ApprovalStep> done = in.alreadyDecided() == null ? List.of() : in.alreadyDecided();
        List<ApprovalStep> chain = new ArrayList<>(done);
        for (Map.Entry<StaffRole, String> e : needed.entrySet()) {
            StaffRole role = e.getKey();
            if (done.stream().anyMatch(s -> s.isApproved() && s.role().equals(role.name()))) {
                continue; // e.g. the line manager already approved the need: not asked twice
            }
            StaffMember who = resolve(role, in, chain, staff);
            chain.add(ApprovalStep.pending(who.role().name(), who.id(), who.name(), who.title(),
                    who.role() == role ? e.getValue() : e.getValue() + " (escalated from " + role.label() + ")",
                    in.stage()));
        }
        return chain;
    }

    /** The person for a role in this department, escalating past anyone who may not decide. */
    StaffMember resolve(StaffRole role, Input in, List<ApprovalStep> chain, List<StaffMember> staff) {
        StaffRole current = role;
        for (int guard = 0; guard < 5; guard++) {
            StaffRole wanted = current;
            StaffMember candidate = staff.stream()
                    .filter(m -> m.role() == wanted && m.actsFor(in.department()))
                    .filter(m -> !m.name().equalsIgnoreCase(in.requesterName()))
                    .filter(m -> chain.stream().noneMatch(s -> s.approverId().equals(m.id())))
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

    private static String euros(BigDecimal amount) {
        return "€" + String.format("%,.0f", amount);
    }
}
