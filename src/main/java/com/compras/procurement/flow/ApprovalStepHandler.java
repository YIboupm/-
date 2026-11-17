package com.compras.procurement.flow;

import com.compras.procurement.approval.ApprovalPolicy;
import com.compras.procurement.approval.StaffRepository;
import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.budget.BudgetVerdict;
import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * APPROVAL: the approval rules of the step's stage decide who signs. Nobody needed (a small catalog item within
 * budget, or everyone already approved an earlier stage) finishes the step at once.
 */
@Component
public class ApprovalStepHandler implements StepHandler {

    private final RequestService requests;
    private final BudgetService budgets;
    private final ApprovalPolicy approvals;
    private final StaffRepository staff;
    private final RequestFacts facts;

    public ApprovalStepHandler(RequestService requests, BudgetService budgets, ApprovalPolicy approvals,
                               StaffRepository staff, RequestFacts facts) {
        this.requests = requests;
        this.budgets = budgets;
        this.approvals = approvals;
        this.staff = staff;
        this.facts = facts;
    }

    @Override
    public String type() {
        return "APPROVAL";
    }

    @Override
    public Outcome enter(Context c) {
        PurchaseRequest r = c.request();
        boolean priced = r.amount() != null && r.amount().signum() > 0;
        boolean over = false;
        if (priced) { // a verdict only; before a price exists there is nothing to check
            BudgetVerdict verdict = budgets.verdict(r.chargeDepartment(), facts.quarterCharge(r));
            requests.recordBudgetCheck(r.getId(), verdict.name());
            over = verdict == BudgetVerdict.OVER_BUDGET;
        }
        List<ApprovalStep> decided = r.getApprovals().stream().filter(ApprovalStep::isApproved).toList();
        List<ApprovalStep> chain = approvals.steps(c.policy(), facts.approval(r, c.step().stage(), over, decided),
                staff.findAll());
        if (chain.stream().noneMatch(ApprovalStep::isPending)) {
            return Outcome.DONE;
        }
        if (r.getStatus() == RequestStatus.PENDING_APPROVAL) {
            requests.updateApprovals(r.getId(), chain); // the next stage follows directly
        } else {
            requests.startApproval(r.getId(), chain, over
                    ? "Over the department's budget: the Finance Controller decides"
                    : priced ? "Waiting for approval" : "The need is approved first; then it is sourced and priced");
        }
        return Outcome.WAITING;
    }

    @Override
    public Outcome resume(Context c) {
        PurchaseRequest r = c.request();
        return r.getStatus() == RequestStatus.PENDING_APPROVAL && r.currentStep() == null ? Outcome.DONE
                : Outcome.WAITING;
    }
}
