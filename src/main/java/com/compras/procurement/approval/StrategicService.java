package com.compras.procurement.approval;

import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import com.compras.procurement.shared.Events;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Policy 7: the category manager decides how a strategic purchase is sourced. Four outcomes; the assistant's
 * conversation is kept as a record for this decision, not used to make it.
 */
@Service
public class StrategicService {

    public enum Outcome {
        /** An existing agreement covers it: a catalog request continues on the standard route, a special one is
         *  returned to the employee to request it there. */
        USE_EXISTING_AGREEMENT,
        /** Run quotes / a tender (policy 5); the award then goes through the approval matrix. */
        SOURCE,
        /** Negotiate a framework agreement; this request is the first call-off. The catalog is updated by hand. */
        NEW_FRAMEWORK,
        REJECT
    }

    private final RequestService requests;
    private final ApprovalService approvals;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public StrategicService(RequestService requests, ApprovalService approvals, ApplicationEventPublisher events,
                            Clock clock) {
        this.requests = requests;
        this.approvals = approvals;
        this.events = events;
        this.clock = clock;
    }

    public List<PurchaseRequest> queue() {
        return requests.inStrategicReview();
    }

    public PurchaseRequest decide(String requestId, String staffId, Outcome outcome, String note) {
        StaffMember who = approvals.member(staffId);
        if (who.role() != StaffRole.CATEGORY_MANAGER) {
            throw new com.compras.procurement.shared.ForbiddenException(
                    "Only a category manager decides strategic purchases (policy 7).");
        }
        String text = note == null ? "" : note.trim();
        if ((outcome == Outcome.REJECT || outcome == Outcome.USE_EXISTING_AGREEMENT) && text.isEmpty()) {
            throw new IllegalStateException("Tell the employee why (they will see it).");
        }
        PurchaseRequest r = requests.recordStrategicDecision(requestId, outcome.name(), who.name(), text);
        events.publishEvent(new Events.StrategicDecided(r.getId(), r.getNumber(), outcome.name(), who.name(), text,
                Instant.now(clock)));
        boolean catalogRequest = !r.isNonStandard() && r.getSourced() == null;
        return switch (outcome) {
            case USE_EXISTING_AGREEMENT -> catalogRequest ? approvals.proceedStandard(requestId)
                    : requests.redirect(requestId, text);
            case SOURCE -> requests.startSourcing(requestId, StaffRole.CATEGORY_MANAGER.name(),
                    "Category manager " + who.name() + " runs quotes / a tender" + (text.isEmpty() ? "" : ": " + text));
            case NEW_FRAMEWORK -> requests.startSourcing(requestId, StaffRole.CATEGORY_MANAGER.name(),
                    "Category manager " + who.name() + " negotiates a framework agreement; this request is the first "
                            + "call-off. Follow-up: add it to the catalog once signed" + (text.isEmpty() ? "" : ": " + text));
            case REJECT -> requests.reject(requestId, "Rejected in strategic review by " + who.name() + ": " + text);
        };
    }

    /** A buyer escalates a request they were sourcing (policy 7: manual submission). */
    public PurchaseRequest escalateByBuyer(String requestId, String reason) {
        PurchaseRequest r = requests.get(requestId);
        if (r.getStatus() != RequestStatus.BUYER_REVIEW && r.getStatus() != RequestStatus.SOURCING) {
            throw new IllegalStateException(r.getNumber() + " is not with a buyer.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Say why it needs a strategic review.");
        }
        return requests.toStrategicReview(requestId, "MANUAL", List.of(reason.trim()), "Buyer");
    }
}
