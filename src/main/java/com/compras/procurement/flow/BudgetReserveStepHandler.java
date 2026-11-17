package com.compras.procurement.flow;

import com.compras.procurement.approval.ApprovalPolicy;
import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.approval.StaffRepository;
import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.budget.BudgetVerdict;
import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * BUDGET_RESERVE: the money is reserved in the department's quarterly pool (policy 8). Going over budget is allowed
 * only when an approver whose step was about the budget said yes; if the money ran out while the chain was running,
 * that approver is asked instead of silently overspending.
 */
@Component
public class BudgetReserveStepHandler implements StepHandler {

    private final RequestService requests;
    private final BudgetService budgets;
    private final ApprovalPolicy approvals;
    private final StaffRepository staff;
    private final RequestFacts facts;

    public BudgetReserveStepHandler(RequestService requests, BudgetService budgets, ApprovalPolicy approvals,
                                    StaffRepository staff, RequestFacts facts) {
        this.requests = requests;
        this.budgets = budgets;
        this.approvals = approvals;
        this.staff = staff;
        this.facts = facts;
    }

    @Override
    public String type() {
        return "BUDGET_RESERVE";
    }

    @Override
    public Outcome enter(Context c) {
        PurchaseRequest r = c.request();
        if (r.getStatus() == RequestStatus.PENDING_APPROVAL && r.currentStep() != null) {
            return Outcome.WAITING; // the budget decision is still open
        }
        boolean accepted = r.getApprovals().stream().anyMatch(s -> ApprovalPolicy.coversOverBudget(c.policy(), s));
        String actor = r.getApprovals().isEmpty() ? "auto-approval"
                : r.getApprovals().get(r.getApprovals().size() - 1).approverName();
        BudgetService.Outcome outcome = budgets.reserve(r.getId(), r.getNumber(), r.chargeDepartment(),
                facts.quarterCharge(r), accepted, actor);
        if (outcome == BudgetService.Outcome.INSUFFICIENT) {
            // the money ran out while this request was being approved (another request reserved it first)
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.OVER_BUDGET.name());
            StaffRole decider = c.policy().approvals().stream().filter(a -> a.budgetDecision())
                    .map(a -> StaffRole.valueOf(a.role())).findFirst().orElse(StaffRole.FINANCE_CONTROLLER);
            List<ApprovalStep> chain = new ArrayList<>(r.getApprovals());
            // asked again even if they approved an earlier step: this is a new decision about the overrun
            StaffMember who = approvals.resolve(decider, facts.approval(r, ApprovalPolicy.SPEND, true, chain),
                    List.of(), staff.findAll());
            chain.add(ApprovalStep.pending(who.role().name(), who.id(), who.name(), who.title(),
                    "The department's budget no longer covers this request (another request was approved first)",
                    ApprovalPolicy.SPEND, List.of(ApprovalStep.BUDGET_RACE_RULE)));
            if (r.getStatus() == RequestStatus.PENDING_APPROVAL) {
                requests.updateApprovals(r.getId(), chain);
            } else {
                requests.startApproval(r.getId(), chain, "Budget no longer sufficient: " + who.name() + " decides");
            }
            return Outcome.WAITING;
        }
        if (outcome == BudgetService.Outcome.RESERVED_OVER_BUDGET) {
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.OVER_BUDGET.name());
        } else if (outcome == BudgetService.Outcome.RESERVED) {
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.WITHIN_BUDGET.name());
        }
        return Outcome.DONE;
    }
}
