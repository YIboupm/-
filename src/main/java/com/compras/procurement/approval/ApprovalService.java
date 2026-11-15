package com.compras.procurement.approval;

import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.budget.BudgetVerdict;
import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import com.compras.procurement.shared.Events;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the approval chain and reserves budget when it completes.
 *
 * <ul>
 *   <li>Standard request: the matrix decides the chain; an empty chain (small catalog item within budget) is
 *       approved at once.</li>
 *   <li>Special request: the line manager first approves the need; a buyer sources it; the real price then goes
 *       through the matrix again (steps already approved are not repeated).</li>
 *   <li>Budget: reserved when the last approver says yes. Over budget is allowed only once the Finance
 *       Controller (or CFO) has approved; if the money ran out while the chain was running, the Finance
 *       Controller is added to the chain instead of silently overspending.</li>
 * </ul>
 */
@Service
public class ApprovalService {

    private static final String SAMPLE_CHANNEL = "demo-loader";

    private final RequestService requests;
    private final com.compras.procurement.policy.PolicyRouter router;
    private final com.compras.procurement.catalog.CatalogService catalog;
    private final ApprovalProperties matrix;
    private final BudgetService budgets;
    private final ApprovalPolicy policy;
    private final StaffRepository staff;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ApprovalService(RequestService requests, BudgetService budgets, ApprovalPolicy policy,
                           StaffRepository staff, ApplicationEventPublisher events, Clock clock,
                           com.compras.procurement.policy.PolicyRouter router,
                           com.compras.procurement.catalog.CatalogService catalog, ApprovalProperties matrix) {
        this.router = router;
        this.catalog = catalog;
        this.matrix = matrix;
        this.requests = requests;
        this.budgets = budgets;
        this.policy = policy;
        this.staff = staff;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    public void on(Events.RequestSubmitted event) {
        PurchaseRequest r = requests.get(event.requestId());
        if (SAMPLE_CHANNEL.equals(r.getChannel())) {
            // sample data for the consolidation demo: approvals skipped, budget still reserved
            budgets.reserve(r.getId(), r.getNumber(), r.chargeDepartment(), quarterCharge(r), true, "sample data");
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.WITHIN_BUDGET.name());
            requests.approve(r.getId(), "Sample data: approval chain skipped, budget reserved");
            return;
        }
        // Policy 4: which route? (split check first: related requests are judged together, policy 2)
        r = applySplitCheck(r);
        com.compras.procurement.policy.RouteDecision route = router.route(routeInput(r));
        r = requests.recordRoute(r.getId(), route.route().name(), route.reasons(), r.getPolicyValue(), r.getSplitNote());
        if (route.strategic()) {
            requests.toStrategicReview(r.getId(), "AUTO", route.reasons(), "Procurement policy");
            return;
        }
        if (r.isNonStandard()) {
            List<ApprovalStep> chain = policy.steps(input(r, ApprovalPolicy.NEED, false, List.of()), staff.findAll());
            requests.startApproval(r.getId(), chain, "Special request: the line manager approves the need first");
            return;
        }
        routeSpend(r, List.of());
    }

    /**
     * An approver sends the request to strategic review instead of approving or rejecting it (policy 6/7).
     * The step is marked ESCALATED; nothing was reserved yet.
     */
    public PurchaseRequest escalate(String requestId, String staffId, String reason) {
        PurchaseRequest r = requests.get(requestId);
        ApprovalStep step = r.currentStep();
        if (r.getStatus() != RequestStatus.PENDING_APPROVAL || step == null || !step.approverId().equals(staffId)) {
            throw new IllegalStateException(r.getNumber() + " is not waiting for your approval.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Say why it needs a strategic review.");
        }
        List<ApprovalStep> chain = new ArrayList<>(r.getApprovals());
        chain.set(chain.indexOf(step), step.escalate(reason.trim(), Instant.now(clock)));
        requests.updateApprovals(requestId, chain);
        return requests.toStrategicReview(requestId, "MANUAL", List.of(reason.trim()),
                step.approverName() + " (" + StaffRole.valueOf(step.role()).label() + ")");
    }

    /** After strategic review decided "use the existing agreement" for a catalog request: the standard route. */
    public PurchaseRequest proceedStandard(String requestId) {
        PurchaseRequest r = requests.get(requestId);
        routeSpend(r, r.getApprovals().stream().filter(ApprovalStep::isApproved).toList());
        return requests.get(requestId);
    }

    /** A buyer priced a special request: the real amount goes through the matrix. */
    @EventListener
    public void on(Events.RequestSourced event) {
        PurchaseRequest r = applySplitCheck(requests.get(event.requestId()));
        // an award above the strategic threshold that was never reviewed goes to the category manager (policy 5)
        if (r.getStrategic() == null) {
            com.compras.procurement.policy.RouteDecision route = router.route(
                    new com.compras.procurement.policy.PolicyRouter.Input(false, false, r.matrixValue(), java.util.Map.of()));
            if (route.strategic()) {
                requests.recordRoute(r.getId(), route.route().name(), route.reasons(), r.getPolicyValue(), r.getSplitNote());
                requests.toStrategicReview(r.getId(), "AUTO", route.reasons(), "Procurement policy");
                return;
            }
        }
        routeSpend(r, r.getApprovals().stream().filter(ApprovalStep::isApproved).toList());
    }

    /** The current approver decides their step. */
    public PurchaseRequest decide(String requestId, String staffId, boolean approve, String note) {
        PurchaseRequest r = requests.get(requestId);
        ApprovalStep step = r.currentStep();
        if (r.getStatus() != RequestStatus.PENDING_APPROVAL || step == null) {
            throw new IllegalStateException(r.getNumber() + " is not waiting for an approval.");
        }
        if (!step.approverId().equals(staffId)) {
            throw new IllegalStateException(r.getNumber() + " is waiting for " + step.approverName() + ", not you.");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new IllegalStateException("Give the employee a reason for the rejection.");
        }
        Instant now = Instant.now(clock);
        List<ApprovalStep> chain = new ArrayList<>(r.getApprovals());
        chain.set(chain.indexOf(step), step.decide(approve, note, now));
        r = requests.updateApprovals(requestId, chain);
        events.publishEvent(new Events.ApprovalDecided(r.getId(), r.getNumber(), step.approverName(), step.role(),
                approve, note, now));

        if (!approve) {
            return requests.reject(requestId, "Rejected by " + step.approverName() + " ("
                    + StaffRole.valueOf(step.role()).label() + "): " + note.trim());
        }
        if (r.currentStep() != null) {
            return r; // next approver
        }
        if (ApprovalPolicy.NEED.equals(step.stage())) {
            return requests.routeToBuyer(requestId, "Need approved by " + step.approverName()
                    + ": a buyer will source and price it");
        }
        return reserveAndApprove(r);
    }

    /** Requests waiting for this person. */
    public List<PurchaseRequest> inbox(String staffId) {
        return requests.all().stream()
                .filter(r -> r.getStatus() == RequestStatus.PENDING_APPROVAL)
                .filter(r -> r.currentStep() != null && r.currentStep().approverId().equals(staffId))
                .toList();
    }

    public List<StaffMember> staff() {
        return staff.findAll();
    }

    public StaffMember member(String staffId) {
        return staff.findById(staffId == null ? "" : staffId)
                .orElseThrow(() -> new IllegalStateException("Unknown staff member"));
    }

    // ------------------------------------------------------------------ internals

    private void routeSpend(PurchaseRequest r, List<ApprovalStep> decided) {
        BudgetVerdict verdict = budgets.verdict(r.chargeDepartment(), quarterCharge(r));
        requests.recordBudgetCheck(r.getId(), verdict.name());
        List<ApprovalStep> chain = policy.steps(input(r, ApprovalPolicy.SPEND, verdict == BudgetVerdict.OVER_BUDGET,
                decided), staff.findAll());
        boolean anyPending = chain.stream().anyMatch(ApprovalStep::isPending);
        if (!anyPending) {
            reserveAndApprove(requests.get(r.getId()));
        } else {
            requests.startApproval(r.getId(), chain, verdict == BudgetVerdict.OVER_BUDGET
                    ? "Over the department's budget: the Finance Controller decides"
                    : "Waiting for approval");
        }
    }

    private PurchaseRequest reserveAndApprove(PurchaseRequest r) {
        // going over budget needs finance to have approved knowingly: a finance step that was about the budget
        boolean financeAccepted = r.getApprovals().stream().anyMatch(s -> s.isApproved()
                && (s.role().equals(StaffRole.FINANCE_CONTROLLER.name()) || s.role().equals(StaffRole.CFO.name()))
                && s.reason() != null && s.reason().toLowerCase().contains("budget"));
        String actor = r.getApprovals().isEmpty() ? "auto-approval"
                : r.getApprovals().get(r.getApprovals().size() - 1).approverName();
        BudgetService.Outcome outcome = budgets.reserve(r.getId(), r.getNumber(), r.chargeDepartment(), quarterCharge(r),
                financeAccepted, actor);
        if (outcome == BudgetService.Outcome.INSUFFICIENT) {
            // the money ran out while this request was being approved (another request reserved it first)
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.OVER_BUDGET.name());
            List<ApprovalStep> chain = new ArrayList<>(r.getApprovals());
            // asked again even if they approved an earlier step: this is a new decision about the overrun
            StaffMember finance = policy.resolve(StaffRole.FINANCE_CONTROLLER,
                    input(r, ApprovalPolicy.SPEND, true, chain), List.of(), staff.findAll());
            chain.add(ApprovalStep.pending(finance.role().name(), finance.id(), finance.name(), finance.title(),
                    "The department's budget no longer covers this request (another request was approved first)",
                    ApprovalPolicy.SPEND));
            return r.getStatus() == RequestStatus.PENDING_APPROVAL
                    ? requests.updateApprovals(r.getId(), chain)
                    : requests.startApproval(r.getId(), chain, "Budget no longer sufficient: the Finance Controller decides");
        }
        boolean over = outcome == BudgetService.Outcome.RESERVED_OVER_BUDGET;
        if (over) {
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.OVER_BUDGET.name());
        }
        String note = r.getApprovals().isEmpty()
                ? "Auto-approved: catalog item within the approval limit and the department's budget"
                : "Approved by " + String.join(", ", r.getApprovals().stream().map(ApprovalStep::approverName).toList());
        return requests.approve(r.getId(), note + (over ? "; over budget, accepted by finance" : "; budget reserved"));
    }

    private com.compras.procurement.policy.PolicyRouter.Input routeInput(PurchaseRequest r) {
        boolean framework = catalog.findCategory(r.getCategoryCode() == null ? "" : r.getCategoryCode())
                .map(c -> c.resolution() == com.compras.procurement.catalog.ResolutionStrategy.CONSUMPTION).orElse(false);
        return new com.compras.procurement.policy.PolicyRouter.Input(r.isNonStandard(), framework, r.matrixValue(),
                r.getAttributes());
    }

    /**
     * Policy 2: requests of the same department and category within the window are judged by their total when
     * that total crosses a threshold the request alone does not.
     */
    private PurchaseRequest applySplitCheck(PurchaseRequest r) {
        java.math.BigDecimal own = r.amount() == null ? java.math.BigDecimal.ZERO : r.amount();
        if (own.signum() <= 0) {
            return r;
        }
        Instant since = Instant.now(clock).minus(java.time.Duration.ofDays(router.policy().splitWindowDays()));
        List<PurchaseRequest> related = requests.all().stream()
                .filter(o -> !o.getId().equals(r.getId()))
                .filter(o -> o.chargeDepartment().equalsIgnoreCase(r.chargeDepartment()) && sameThing(o, r))
                .filter(o -> o.getSubmittedAt() != null && o.getSubmittedAt().isAfter(since))
                .filter(o -> !java.util.EnumSet.of(RequestStatus.REJECTED, RequestStatus.CANCELLED,
                        RequestStatus.REDIRECTED).contains(o.getStatus()))
                .filter(o -> o.amount() != null && o.amount().signum() > 0)
                .toList();
        java.math.BigDecimal total = related.stream().map(PurchaseRequest::amount)
                .reduce(own, java.math.BigDecimal::add);
        boolean crosses = java.util.stream.Stream.of(matrix.autoApproveUpTo(), matrix.managerUpTo(),
                        matrix.cfoAbove(), router.policy().strategicAbove())
                .anyMatch(t -> own.compareTo(t) <= 0 && total.compareTo(t) > 0);
        if (!crosses) {
            return requests.recordRoute(r.getId(), r.getRoute(), r.getRouteReasons(), own, null);
        }
        String note = "Policy 2: with " + String.join(", ", related.stream().map(PurchaseRequest::getNumber).toList())
                + " (same department and category, last " + router.policy().splitWindowDays() + " days) the total is "
                + com.compras.procurement.policy.PolicyRouter.euros(total) + "; approvals use that total";
        return requests.recordRoute(r.getId(), r.getRoute(), r.getRouteReasons(), total, note);
    }

    /**
     * "The same need": the same catalog category; for non-catalog requests (no meaningful category) the same
     * awarded supplier.
     */
    private static boolean sameThing(PurchaseRequest a, PurchaseRequest b) {
        boolean catalogA = !a.isNonStandard() && a.getSourced() == null;
        boolean catalogB = !b.isNonStandard() && b.getSourced() == null;
        if (catalogA && catalogB) {
            return java.util.Objects.equals(a.getCategoryCode(), b.getCategoryCode());
        }
        return a.getSourced() != null && b.getSourced() != null
                && a.getSourced().supplierName().equalsIgnoreCase(b.getSourced().supplierName());
    }

    /** The current quarter's share: all of it for a purchase, the remaining months of it for a cloud call-off. */
    private java.math.BigDecimal quarterCharge(PurchaseRequest r) {
        return com.compras.procurement.request.SpendProfile.quarterCharge(r, java.time.LocalDate.now(clock));
    }

    private ApprovalPolicy.Input input(PurchaseRequest r, String stage, boolean overBudget, List<ApprovalStep> decided) {
        return new ApprovalPolicy.Input(stage, !r.isNonStandard() && r.getSourced() == null, r.matrixValue(),
                r.getCategoryCode(), r.chargeDepartment(),
                r.getRequester().name(), overBudget, decided, r.getAttributes());
    }
}
