package com.compras.procurement.request;

import com.compras.procurement.context.Employee;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.resolution.FieldValues;
import com.compras.procurement.resolution.ResolvedLine;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.Money;
import com.compras.procurement.shared.NotFoundException;
import com.compras.procurement.shared.SequenceService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the purchase request and its state machine. Every state change is saved and then
 * announced as a domain event; downstream modules react to the events.
 */
@Service
public class RequestService {

    private final PurchaseRequestRepository repository;
    private final SequenceService sequences;
    private final ApplicationEventPublisher events;
    private final EmployeeContextService employees;
    private final Clock clock;

    public RequestService(PurchaseRequestRepository repository, SequenceService sequences,
                          ApplicationEventPublisher events, EmployeeContextService employees, Clock clock) {
        this.repository = repository;
        this.sequences = sequences;
        this.events = events;
        this.employees = employees;
        this.clock = clock;
    }

    public PurchaseRequest submit(NewRequest input) {
        Employee e = input.requester().employee();
        List<PurchaseRequest.Line> lines = new ArrayList<>();
        int n = 1;
        for (ResolvedLine l : input.lines()) {
            lines.add(new PurchaseRequest.Line(String.valueOf(n++), l.sku(), l.name(), l.categoryCode(), l.quantity(),
                    l.unitPrice(), l.lineTotal(), l.supplierId(), l.supplierName(), l.leadTimeDays(), l.specs(), l.note()));
        }
        Instant now = Instant.now(clock);
        String department = FieldValues.string(input.attributes(), "chargeTo").orElse(e.department());
        String costCenter = employees.department(department).map(d -> d.costCenter()).orElse(e.costCenter());
        PurchaseRequest request = new PurchaseRequest(
                sequences.nextNumber("PR"),
                input.conversationId(),
                input.channel(),
                new PurchaseRequest.Requester(e.id(), e.name(), e.department(), e.costCenter(), e.site()),
                new PurchaseRequest.ChargeTo(department, costCenter),
                input.originalRequest(),
                input.mappingRationale(),
                input.requirementChecks(),
                input.category().code(),
                input.category().name(),
                input.attributes(),
                input.attributeSources(),
                lines,
                input.estimatedTotal(),
                input.currency(),
                FieldValues.date(input.attributes(), "neededBy").orElse(null),
                FieldValues.string(input.attributes(), "deliverySite").orElse(e.site()),
                FieldValues.flag(input.attributes(), "urgent"),
                input.warnings(),
                input.specialRequirements(),
                now);
        request.describe(input.title(), input.requirements(), input.alternativesConsidered(), input.suggestedModels());
        request = repository.save(request);
        events.publishEvent(new Events.RequestSubmitted(request.getId(), request.getNumber(), request.isNonStandard(), now));
        return request;
    }

    public PurchaseRequest approve(String requestId, String note) {
        PurchaseRequest request = transition(requestId, RequestStatus.APPROVED, note);
        events.publishEvent(new Events.RequestApproved(request.getId(), request.isUrgent(), note, Instant.now(clock)));
        return request;
    }

    /** Special requests are not auto-approved: a buyer sources and prices them first. */
    public PurchaseRequest routeToBuyer(String requestId, String note) {
        return transition(requestId, RequestStatus.BUYER_REVIEW, note);
    }

    /** A buyer sourced a special request: record what will be bought and approve it. */
    public PurchaseRequest buyerApprove(String requestId, BuyerApproval a) {
        String description = a.description() == null ? "" : a.description().trim();
        String supplierName = a.supplierName() == null ? "" : a.supplierName().trim();
        if (description.isEmpty() || supplierName.isEmpty() || a.quantity() < 1 || a.unitPrice() == null
                || a.unitPrice().signum() <= 0) {
            throw new IllegalStateException("Item, supplier, quantity and a positive unit price are required.");
        }
        PurchaseRequest request = get(requestId);
        if (request.getStatus() != RequestStatus.BUYER_REVIEW) {
            throw new IllegalStateException("Request " + request.getNumber() + " is not waiting for a buyer.");
        }
        // The bought item must meet every must-have the employee stated; the buyer confirms each one.
        java.util.Map<String, Boolean> answers = new java.util.HashMap<>();
        if (a.confirmations() != null) {
            a.confirmations().forEach(c -> answers.put(c.requirement(), c.met()));
        }
        List<String> problems = new java.util.ArrayList<>();
        List<BuyerApproval.Confirmation> confirmations = new java.util.ArrayList<>();
        for (var r : request.getRequirements()) {
            Boolean met = answers.get(r.description());
            if (r.isMust() && met == null) {
                problems.add("Confirm whether the item meets: " + r.description());
            } else if (r.isMust() && !met) {
                problems.add("The item does not meet the must-have \"" + r.description()
                        + "\": source another item, or reject the request with the reason.");
            }
            if (met != null) {
                confirmations.add(new BuyerApproval.Confirmation(r.description(), met));
            }
        }
        // Buying something other than what the employee chose needs a reason the employee will see.
        String chosen = request.chosenModelLabel();
        boolean substituted = chosen != null && !description.equalsIgnoreCase(chosen.trim());
        String reason = a.substitutionReason() == null ? null : a.substitutionReason().trim();
        if (substituted && (reason == null || reason.isEmpty())) {
            problems.add("You are buying something other than the employee's choice (" + chosen
                    + "). Say why, e.g. \"model discontinued\".");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" ", problems));
        }
        Instant now = Instant.now(clock);
        int quantity = a.quantity();
        BigDecimal unitPrice = a.unitPrice();
        request.recordSourcing(new PurchaseRequest.SourcedItem(description, supplierName, quantity,
                unitPrice, Money.times(unitPrice, quantity), a.note(), now, chosen, substituted,
                substituted ? reason : null, confirmations));
        String approvalNote = "Approved by buyer: " + description + " from " + supplierName
                + (substituted ? " (instead of " + chosen + ": " + reason + ")" : "");
        request.moveTo(RequestStatus.APPROVED, now, approvalNote);
        request = repository.save(request);
        events.publishEvent(new Events.RequestApproved(request.getId(), false, approvalNote, now));
        return request;
    }

    public PurchaseRequest reject(String requestId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Give the employee a reason for the rejection.");
        }
        PurchaseRequest request = get(requestId);
        request.recordRejection(reason.trim());
        request.moveTo(RequestStatus.REJECTED, Instant.now(clock), "Rejected: " + reason.trim());
        request = repository.save(request);
        events.publishEvent(new Events.RequestRejected(request.getId(), request.getNumber(), reason.trim(),
                Instant.now(clock)));
        return request;
    }

    /** One employee's requests, newest first: what they asked for and what happened. */
    public List<PurchaseRequest> of(String employeeId) {
        return repository.findAll().stream()
                .filter(r -> r.getRequester() != null && employeeId.equals(r.getRequester().employeeId()))
                .sorted(java.util.Comparator.comparing(PurchaseRequest::getSubmittedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }

    public List<PurchaseRequest> waitingForBuyer() {
        return repository.findByStatusOrderBySubmittedAtAsc(RequestStatus.BUYER_REVIEW);
    }

    public PurchaseRequest markPooled(String requestId) {
        return transition(requestId, RequestStatus.POOLED, "Lines added to the demand pool");
    }

    public PurchaseRequest markOrdered(String requestId, List<String> orderNumbers) {
        PurchaseRequest request = transition(requestId, RequestStatus.ORDERED,
                "On purchase order(s) " + String.join(", ", orderNumbers));
        events.publishEvent(new Events.RequestOrdered(requestId, orderNumbers, Instant.now(clock)));
        return request;
    }

    public PurchaseRequest get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Unknown request " + id));
    }

    public List<PurchaseRequest> all() {
        return repository.findAllByOrderBySubmittedAtDesc();
    }

    private PurchaseRequest transition(String requestId, RequestStatus target, String note) {
        PurchaseRequest request = get(requestId);
        request.moveTo(target, Instant.now(clock), note);
        return repository.save(request);
    }
}
