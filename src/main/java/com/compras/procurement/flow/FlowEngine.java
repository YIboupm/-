package com.compras.procurement.flow;

import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.budget.BudgetVerdict;
import com.compras.procurement.policy.PolicyRouter;
import com.compras.procurement.policy.PolicySet;
import com.compras.procurement.policy.PolicySource;
import com.compras.procurement.policy.RouteDecision;
import com.compras.procurement.request.FlowStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import com.compras.procurement.shared.Events;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Moves a request along its route. The route is data: the policy version's routing table picks it, and it is an
 * ordered list of steps. This engine knows no route by name; it only runs the next step's {@link StepHandler} and
 * stops whenever a step waits for a person.
 *
 * <p>A request keeps the policy version it was submitted under until it ends, so a policy change never re-judges
 * requests in flight.
 */
@Service
public class FlowEngine {

    private static final String SAMPLE_CHANNEL = "demo-loader";

    private final RequestService requests;
    private final PolicySource policies;
    private final PolicyRouter router;
    private final RequestFacts facts;
    private final BudgetService budgets;
    private final Clock clock;
    private final Map<String, StepHandler> handlers = new LinkedHashMap<>();

    public FlowEngine(RequestService requests, PolicySource policies, PolicyRouter router, RequestFacts facts,
                      BudgetService budgets, List<StepHandler> handlers, Clock clock) {
        this.requests = requests;
        this.policies = policies;
        this.router = router;
        this.facts = facts;
        this.budgets = budgets;
        this.clock = clock;
        handlers.forEach(h -> this.handlers.put(h.type(), h));
    }

    // ------------------------------------------------------------------ what starts or continues a flow

    @EventListener
    public void on(Events.RequestSubmitted event) {
        PurchaseRequest r = requests.get(event.requestId());
        if (SAMPLE_CHANNEL.equals(r.getChannel())) {
            // sample data for the consolidation demo: approvals skipped, budget still reserved
            budgets.reserve(r.getId(), r.getNumber(), r.chargeDepartment(), facts.quarterCharge(r), true, "sample data");
            requests.recordBudgetCheck(r.getId(), BudgetVerdict.WITHIN_BUDGET.name());
            requests.approve(r.getId(), "Sample data: approval chain skipped, budget reserved");
            return;
        }
        PolicySet policy = policies.active();
        // Policy 2 first: related requests are judged together. Then policy 4: which route?
        r = applySplitCheck(r, policy);
        RouteDecision decision = router.route(policy, facts.routing(r));
        requests.recordRoute(r.getId(), decision.route(), decision.reasons(), r.getPolicyValue(), r.getSplitNote());
        requests.recordFlow(r.getId(), policy.version(), plan(policy, decision.route()));
        run(r.getId(), false);
    }

    /** A buyer (or the category manager) awarded: the real amount is known now. */
    @EventListener
    public void on(Events.RequestSourced event) {
        PurchaseRequest r = adopt(requests.get(event.requestId()));
        PolicySet policy = policies.version(r.getPolicyVersion());
        r = applySplitCheck(r, policy);
        // an award above the strategic threshold that was never reviewed goes to the category manager (policy 5)
        if (r.getStrategic() == null) {
            RouteDecision decision = router.route(policy, facts.routingAfterAward(r));
            if (decision.strategic()) {
                detour(r.getId(), "AUTO", decision.reasons(), "Procurement policy");
                return;
            }
        }
        run(r.getId(), true);
    }

    /** Someone acted on the step the request was waiting at (an approval was given, a decision recorded). */
    public PurchaseRequest resume(String requestId) {
        adopt(requests.get(requestId));
        run(requestId, true);
        return requests.get(requestId);
    }

    /**
     * Leaves the current route for the strategic one (policy 7): raised by the rules when a price turns out to be
     * above the threshold, or by an approver or buyer by hand.
     */
    public PurchaseRequest detour(String requestId, String source, List<String> reasons, String by) {
        PurchaseRequest r = requests.get(requestId);
        PolicySet policy = policies.version(r.getPolicyVersion());
        String route = policy.strategicRoute();
        List<FlowStep> flow = new ArrayList<>(plan(policy, route));
        flow.set(0, flow.get(0).with(FlowStep.ACTIVE));
        requests.recordRoute(requestId, route, reasons, r.getPolicyValue(), r.getSplitNote());
        requests.recordFlow(requestId, policy.version(), flow);
        return requests.toStrategicReview(requestId, source, reasons, by);
    }

    /**
     * The category manager decided to go ahead. {@code skipSourcing}: an existing agreement covers it, so nothing
     * has to be sourced and the request continues with the approvals.
     */
    public PurchaseRequest strategicDecided(String requestId, boolean skipSourcing) {
        PurchaseRequest r = adopt(requests.get(requestId));
        if (skipSourcing) {
            List<FlowStep> flow = new ArrayList<>(r.getFlow());
            for (int i = 0; i < flow.size(); i++) {
                if ("SOURCING".equals(flow.get(i).type()) && flow.get(i).open()) {
                    flow.set(i, flow.get(i).with(FlowStep.SKIPPED));
                }
            }
            requests.recordFlow(requestId, r.getPolicyVersion(), flow);
        }
        run(requestId, true);
        return requests.get(requestId);
    }

    /**
     * A sourced order was cancelled before delivery: the route goes back to its sourcing step; the steps after it
     * (approval of the price, budget, order) run again for the next award.
     */
    public PurchaseRequest backToSourcing(String requestId) {
        PurchaseRequest r = adopt(requests.get(requestId));
        List<FlowStep> flow = new ArrayList<>(r.getFlow());
        int sourcing = -1;
        for (int i = 0; i < flow.size(); i++) {
            if ("SOURCING".equals(flow.get(i).type())) {
                sourcing = i;
            }
        }
        if (sourcing < 0) {
            throw new IllegalStateException(r.getNumber() + " has no sourcing step to go back to.");
        }
        for (int i = sourcing; i < flow.size(); i++) {
            flow.set(i, flow.get(i).with(i == sourcing ? FlowStep.ACTIVE : FlowStep.PENDING));
        }
        return requests.recordFlow(requestId, r.getPolicyVersion(), flow);
    }

    /** A rejected or cancelled request: the step it was at shows that it stopped there. */
    @EventListener
    public void on(Events.RequestRejected event) {
        stop(event.requestId(), FlowStep.REJECTED);
    }

    @EventListener
    public void on(Events.RequestCancelled event) {
        stop(event.requestId(), FlowStep.CANCELLED);
    }

    private void stop(String requestId, String status) {
        PurchaseRequest r = requests.get(requestId);
        if (r.getFlow().isEmpty()) {
            return;
        }
        List<FlowStep> flow = new ArrayList<>(r.getFlow());
        for (int i = 0; i < flow.size(); i++) {
            if (FlowStep.ACTIVE.equals(flow.get(i).status())) {
                flow.set(i, flow.get(i).with(status));
            }
        }
        requests.recordFlow(requestId, r.getPolicyVersion(), flow);
    }

    // ------------------------------------------------------------------ the engine

    private void run(String requestId, boolean resuming) {
        for (int guard = 0; guard < 25; guard++) {
            PurchaseRequest r = requests.get(requestId);
            if (EnumSet.of(RequestStatus.REJECTED, RequestStatus.CANCELLED, RequestStatus.REDIRECTED,
                    RequestStatus.RECEIVED, RequestStatus.CLOSED).contains(r.getStatus())) {
                return;
            }
            List<FlowStep> flow = new ArrayList<>(r.getFlow());
            int index = -1;
            for (int i = 0; i < flow.size() && index < 0; i++) {
                index = flow.get(i).open() ? i : -1;
            }
            if (index < 0) {
                return; // the route is finished
            }
            FlowStep step = flow.get(index);
            StepHandler handler = handlers.get(step.type());
            if (handler == null) {
                throw new IllegalStateException("No building block for step type " + step.type());
            }
            StepHandler.Context context = new StepHandler.Context(r, step, policies.version(r.getPolicyVersion()));
            boolean waitingHere = resuming && FlowStep.ACTIVE.equals(step.status());
            StepHandler.Outcome outcome = waitingHere ? handler.resume(context) : handler.enter(context);
            resuming = false;
            // handlers save the request themselves: re-read before recording progress
            PurchaseRequest now = requests.get(requestId);
            List<FlowStep> latest = new ArrayList<>(now.getFlow());
            latest.set(index, step.with(outcome == StepHandler.Outcome.DONE ? FlowStep.DONE : FlowStep.ACTIVE));
            requests.recordFlow(requestId, now.getPolicyVersion(), latest);
            if (outcome == StepHandler.Outcome.WAITING) {
                return;
            }
        }
        throw new IllegalStateException("The route of request " + requestId + " does not end.");
    }

    private static List<FlowStep> plan(PolicySet policy, String route) {
        return policy.steps(route).stream()
                .map(s -> new FlowStep(s.type(), s.stage(), s.owner(), s.label(), FlowStep.PENDING)).toList();
    }

    /**
     * A request from before routes were data has no flow: give it the one its route has today, positioned where its
     * status says it is, so it finishes normally.
     */
    private PurchaseRequest adopt(PurchaseRequest r) {
        if (!r.getFlow().isEmpty()) {
            return r;
        }
        PolicySet policy = policies.version(r.getPolicyVersion());
        String route = r.getStrategic() != null ? policy.strategicRoute()
                : r.getRoute() != null && policy.routes().containsKey(r.getRoute()) ? r.getRoute()
                : router.route(policy, facts.routing(r)).route();
        List<FlowStep> flow = new ArrayList<>(plan(policy, route));
        int at = -1;
        for (int i = 0; i < flow.size() && at < 0; i++) {
            FlowStep s = flow.get(i);
            boolean here = switch (r.getStatus()) {
                case STRATEGIC_REVIEW -> "STRATEGIC_REVIEW".equals(s.type());
                case BUYER_REVIEW, SOURCING -> "SOURCING".equals(s.type());
                case PENDING_APPROVAL -> {
                    String stage = r.currentStep() != null ? r.currentStep().stage()
                            : r.getApprovals().isEmpty() ? null
                            : r.getApprovals().get(r.getApprovals().size() - 1).stage();
                    yield "APPROVAL".equals(s.type()) && Objects.equals(s.stage(), stage);
                }
                default -> false;
            };
            at = here ? i : -1;
        }
        if (at < 0) {
            return r; // finished or not started: nothing to continue
        }
        for (int i = 0; i < flow.size(); i++) {
            flow.set(i, flow.get(i).with(i < at ? FlowStep.DONE : i == at ? FlowStep.ACTIVE : FlowStep.PENDING));
        }
        return requests.recordFlow(r.getId(), policy.version(), flow);
    }

    // ------------------------------------------------------------------ policy 2: the split check

    /**
     * Requests of the same department and category within the window are judged by their total when that total
     * crosses a threshold the request alone does not. The thresholds are whatever the rules compare values with.
     */
    private PurchaseRequest applySplitCheck(PurchaseRequest r, PolicySet policy) {
        BigDecimal own = r.amount() == null ? BigDecimal.ZERO : r.amount();
        if (own.signum() <= 0) {
            return r;
        }
        Instant since = Instant.now(clock).minus(Duration.ofDays(policy.splitWindowDays()));
        List<PurchaseRequest> related = requests.all().stream()
                .filter(o -> !o.getId().equals(r.getId()))
                .filter(o -> o.chargeDepartment().equalsIgnoreCase(r.chargeDepartment()) && sameThing(o, r))
                .filter(o -> o.getSubmittedAt() != null && o.getSubmittedAt().isAfter(since))
                .filter(o -> !EnumSet.of(RequestStatus.REJECTED, RequestStatus.CANCELLED, RequestStatus.REDIRECTED)
                        .contains(o.getStatus()))
                .filter(o -> o.amount() != null && o.amount().signum() > 0)
                .toList();
        BigDecimal total = related.stream().map(PurchaseRequest::amount).reduce(own, BigDecimal::add);
        boolean crosses = policy.valueThresholds().stream()
                .anyMatch(t -> own.compareTo(t) <= 0 && total.compareTo(t) > 0);
        if (!crosses) {
            return requests.recordRoute(r.getId(), r.getRoute(), r.getRouteReasons(), own, null);
        }
        String note = "Policy 2: with " + String.join(", ", related.stream().map(PurchaseRequest::getNumber).toList())
                + " (same department and category, last " + policy.splitWindowDays() + " days) the total is "
                + PolicyRouter.euros(total) + "; approvals use that total";
        return requests.recordRoute(r.getId(), r.getRoute(), r.getRouteReasons(), total, note);
    }

    /**
     * "The same need": the same catalog category; for non-catalog requests (no meaningful category) the same
     * awarded supplier.
     */
    private static boolean sameThing(PurchaseRequest a, PurchaseRequest b) {
        if (RequestFacts.catalogItem(a) && RequestFacts.catalogItem(b)) {
            return Objects.equals(a.getCategoryCode(), b.getCategoryCode());
        }
        return a.getSourced() != null && b.getSourced() != null
                && a.getSourced().supplierName().equalsIgnoreCase(b.getSourced().supplierName());
    }
}
