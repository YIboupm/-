package com.compras.procurement.flow;

import com.compras.procurement.approval.StaffMember;
import com.compras.procurement.approval.StaffRepository;
import com.compras.procurement.approval.StaffRole;
import com.compras.procurement.budget.BudgetService;
import com.compras.procurement.consolidation.ConsolidatedOrder;
import com.compras.procurement.consolidation.ConsolidationService;
import com.compras.procurement.receiving.ReceivingService;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.ForbiddenException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * The correction path for a sourced order that turns out wrong before anything is delivered (wrong item, a
 * quote that covered only part of the line, a wrong price): the purchase order is cancelled with the supplier,
 * the commitment goes back to the department's budget, the approvals of that price lapse, and the request is
 * sourced again. Once goods have been checked in, this is no longer possible: they are returned at goods-in.
 */
@Service
public class SourcingReopenService {

    private final RequestService requests;
    private final ConsolidationService consolidation;
    private final ReceivingService receiving;
    private final BudgetService budgets;
    private final FlowEngine flow;
    private final StaffRepository staff;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SourcingReopenService(RequestService requests, ConsolidationService consolidation, ReceivingService receiving,
                                 BudgetService budgets, FlowEngine flow, StaffRepository staff,
                                 ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.consolidation = consolidation;
        this.receiving = receiving;
        this.budgets = budgets;
        this.flow = flow;
        this.staff = staff;
        this.events = events;
        this.clock = clock;
    }

    public PurchaseRequest reopen(String requestId, String staffId, String reason) {
        PurchaseRequest r = requests.get(requestId);
        StaffMember who = staff.findById(staffId == null ? "" : staffId)
                .orElseThrow(() -> new IllegalStateException("Unknown staff member"));
        StaffRole owner = "CATEGORY_MANAGER".equals(r.getSourcingOwner()) ? StaffRole.CATEGORY_MANAGER : StaffRole.BUYER;
        if (who.role() != owner) {
            throw new ForbiddenException(r.getNumber() + " was sourced by the " + owner.label()
                    + ": only they can cancel its order.");
        }
        if (r.getStatus() != RequestStatus.ORDERED || r.getSourced() == null) {
            throw new IllegalStateException(r.getNumber() + " is not a sourced request waiting for delivery.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Say what was wrong with the order: the supplier and the employee see it.");
        }
        if (receiving.anythingReceived(requestId)) {
            throw new IllegalStateException("Goods of " + r.getNumber() + " were already checked in. Return them at "
                    + "goods-in (send back) instead of cancelling the order.");
        }
        String why = reason.trim();
        List<ConsolidatedOrder> orders = consolidation.specialOrdersOf(requestId);
        orders.forEach(o -> consolidation.cancel(o, why));
        long released = budgets.cancelCommitment(requestId, r.getNumber(), who.name(), why);
        requests.reopenSourcing(requestId, who.name(), why);
        flow.backToSourcing(requestId);
        events.publishEvent(new Events.RequestReopened(requestId, r.getNumber(),
                orders.stream().map(ConsolidatedOrder::number).toList(), BudgetService.euros(released), why, who.name(),
                Instant.now(clock)));
        return requests.get(requestId);
    }

    /** Sourced requests that are ordered and not delivered yet: the buyer can still correct them. */
    public List<PurchaseRequest> correctable() {
        return requests.all().stream()
                .filter(r -> r.getStatus() == RequestStatus.ORDERED && r.getSourced() != null)
                .filter(r -> !receiving.anythingReceived(r.getId()))
                .toList();
    }
}
