package com.compras.procurement.web;

import com.compras.procurement.approval.ApprovalService;
import com.compras.procurement.approval.BudgetAccess;
import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Approvers' inbox and the finance budget view. Identity is a header in this demo (X-Staff-Id); in production
 * it comes from single sign-on, and the same checks apply.
 */
@RestController
@RequestMapping("/api")
public class ApprovalController {

    public record InboxItem(PurchaseRequest request, ApprovalStep step, BudgetAccess.DepartmentBudget budget) {
    }

    public record Decision(boolean approve, String note) {
    }

    public record StrategicDecision(com.compras.procurement.approval.StrategicService.Outcome outcome, String note) {
    }

    private final ApprovalService approvals;
    private final BudgetAccess budgets;
    private final com.compras.procurement.approval.StrategicService strategic;

    public ApprovalController(ApprovalService approvals, BudgetAccess budgets,
                              com.compras.procurement.approval.StrategicService strategic) {
        this.approvals = approvals;
        this.budgets = budgets;
        this.strategic = strategic;
    }

    /** The approver's third option (policy 6): send it to strategic review, with the reason. */
    @PostMapping("/approvals/{requestId}/strategic")
    public PurchaseRequest escalate(@PathVariable String requestId, @RequestHeader("X-Staff-Id") String staffId,
                                    @RequestBody Decision body) {
        return approvals.escalate(requestId, staffId, body.note());
    }

    /** The category manager's queue (policy 7). */
    @GetMapping("/strategic")
    public List<PurchaseRequest> strategicQueue() {
        return strategic.queue();
    }

    @PostMapping("/strategic/{requestId}/decide")
    public PurchaseRequest decideStrategic(@PathVariable String requestId, @RequestHeader("X-Staff-Id") String staffId,
                                           @RequestBody StrategicDecision body) {
        return strategic.decide(requestId, staffId, body.outcome(), body.note());
    }

    @GetMapping("/staff")
    public List<StaffMember> staff() {
        return approvals.staff();
    }

    @GetMapping("/approvals")
    public List<InboxItem> inbox(@RequestHeader("X-Staff-Id") String staffId) {
        return approvals.inbox(staffId).stream()
                .map(r -> new InboxItem(r, r.currentStep(), budgets.forApprover(staffId, r.chargeDepartment())))
                .toList();
    }

    @PostMapping("/approvals/{requestId}/decide")
    public PurchaseRequest decide(@PathVariable String requestId, @RequestHeader("X-Staff-Id") String staffId,
                                  @RequestBody Decision body) {
        return approvals.decide(requestId, staffId, body.approve(), body.note());
    }

    @GetMapping("/budgets")
    public BudgetAccess.Overview budgets(@RequestHeader("X-Staff-Id") String staffId) {
        return budgets.overview(staffId);
    }
}
