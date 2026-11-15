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

    /** Puts the request in front of its approval chain (the first pending step decides next). */
    public PurchaseRequest startApproval(String requestId, List<ApprovalStep> steps, String note) {
        PurchaseRequest request = get(requestId);
        request.setApprovals(steps);
        request.moveTo(RequestStatus.PENDING_APPROVAL, Instant.now(clock), note);
        request = repository.save(request);
        announceCurrentStep(request);
        return request;
    }

    /** Saves a decided step (and any step added after it) and announces whoever is next. */
    public PurchaseRequest updateApprovals(String requestId, List<ApprovalStep> steps) {
        PurchaseRequest request = get(requestId);
        request.setApprovals(steps);
        request = repository.save(request);
        announceCurrentStep(request);
        return request;
    }

    public PurchaseRequest recordBudgetCheck(String requestId, String verdict) {
        PurchaseRequest request = get(requestId);
        request.setBudgetCheck(verdict);
        return repository.save(request);
    }

    /** The requester withdraws a request that has not been ordered yet; anything held is released. */
    public PurchaseRequest cancel(String requestId, String employeeId, String reason) {
        PurchaseRequest request = get(requestId);
        if (employeeId == null || !employeeId.equals(request.getRequester().employeeId())) {
            throw new IllegalStateException("Only the requester can cancel " + request.getNumber() + ".");
        }
        String why = reason == null || reason.isBlank() ? "Cancelled by the requester" : reason.trim();
        request.moveTo(RequestStatus.CANCELLED, Instant.now(clock), why);
        request = repository.save(request);
        events.publishEvent(new Events.RequestCancelled(request.getId(), request.getNumber(),
                request.getRequester().name(), why, Instant.now(clock)));
        return request;
    }

    private void announceCurrentStep(PurchaseRequest request) {
        ApprovalStep step = request.currentStep();
        if (step != null && request.getStatus() == RequestStatus.PENDING_APPROVAL) {
            events.publishEvent(new Events.ApprovalRequested(request.getId(), request.getNumber(), step.approverName(),
                    step.role(), step.reason(), Instant.now(clock)));
        }
    }

    public PurchaseRequest recordRoute(String requestId, String route, List<String> reasons,
                                       BigDecimal policyValue, String splitNote) {
        PurchaseRequest request = get(requestId);
        request.recordRoute(route, reasons, policyValue, splitNote);
        return repository.save(request);
    }

    /** Policy 7: the category manager decides how this is sourced. Nothing has been reserved yet. */
    public PurchaseRequest toStrategicReview(String requestId, String source, List<String> reasons, String by) {
        PurchaseRequest request = get(requestId);
        Instant now = Instant.now(clock);
        request.setStrategic(new StrategicCase(source, reasons, by, now, null, null, null, null));
        request.recordSourcing(null); // a price found before the review is not an award
        request.moveTo(RequestStatus.STRATEGIC_REVIEW, now, ("MANUAL".equals(source) ? "Sent to strategic review by "
                + by : "Strategic purchase") + ": " + String.join("; ", reasons));
        request = repository.save(request);
        events.publishEvent(new Events.StrategicReviewRequested(request.getId(), request.getNumber(), source,
                reasons, by, now));
        return request;
    }

    /** The category manager's decision is recorded on the case; the caller moves the request on. */
    public PurchaseRequest recordStrategicDecision(String requestId, String outcome, String by, String note) {
        PurchaseRequest request = get(requestId);
        if (request.getStatus() != RequestStatus.STRATEGIC_REVIEW || request.getStrategic() == null) {
            throw new IllegalStateException(request.getNumber() + " is not in strategic review.");
        }
        request.setStrategic(request.getStrategic().decide(outcome, by, note, Instant.now(clock)));
        return repository.save(request);
    }

    /** Strategic review: an existing agreement covers it; the employee requests it there. */
    public PurchaseRequest redirect(String requestId, String note) {
        PurchaseRequest request = get(requestId);
        request.recordRejection(note);
        request.moveTo(RequestStatus.REDIRECTED, Instant.now(clock), "Use an existing agreement: " + note);
        request = repository.save(request);
        events.publishEvent(new Events.RequestRejected(request.getId(), request.getNumber(), note, Instant.now(clock)));
        return request;
    }

    /** A buyer accepts a special request, or the category manager decides to source a strategic one. */
    public PurchaseRequest startSourcing(String requestId, String owner, String note) {
        PurchaseRequest request = get(requestId);
        request.setSourcingOwner(owner);
        request.moveTo(RequestStatus.SOURCING, Instant.now(clock), note);
        return repository.save(request);
    }

    public PurchaseRequest addQuote(String requestId, Quote quote) {
        PurchaseRequest request = get(requestId);
        if (request.getStatus() != RequestStatus.SOURCING) {
            throw new IllegalStateException(request.getNumber() + " is not being sourced: accept it first.");
        }
        if (quote.supplierName() == null || quote.supplierName().isBlank() || quote.unitPrice() == null
                || quote.unitPrice().signum() <= 0 || quote.quantity() < 1) {
            throw new IllegalStateException("A quote needs a supplier, a quantity and a positive unit price.");
        }
        request.addQuote(new Quote(quote.supplierName().trim(), quote.description(), quote.quantity(),
                quote.unitPrice(), Money.times(quote.unitPrice(), quote.quantity()), quote.reference(),
                quote.note(), quote.addedBy(), Instant.now(clock)));
        return repository.save(request);
    }

    /**
     * Award one of the recorded quotes (policy 5: enough quotes for the value, or a written reason).
     *
     * @param quotesRequired from the policy for this award value
     */
    public PurchaseRequest award(String requestId, int quoteIndex, String description, String substitutionReason,
                                 List<BuyerApproval.Confirmation> confirmations, String quoteException, String note,
                                 java.util.function.ToIntFunction<BigDecimal> quotesRequiredFor) {
        PurchaseRequest request = get(requestId);
        if (request.getStatus() != RequestStatus.SOURCING) {
            throw new IllegalStateException(request.getNumber() + " is not being sourced.");
        }
        List<Quote> quotes = request.getQuotes();
        if (quoteIndex < 0 || quoteIndex >= quotes.size()) {
            throw new IllegalStateException("Choose one of the recorded quotes.");
        }
        Quote q = quotes.get(quoteIndex);
        int required = quotesRequiredFor.applyAsInt(q.total());
        if (quotes.size() < required && (quoteException == null || quoteException.isBlank())) {
            throw new IllegalStateException("Policy 5: an award of " + q.total() + " EUR needs " + required
                    + " quotes; " + quotes.size() + " recorded. Add quotes, or say why fewer were possible.");
        }
        String noteText = quotes.size() < required ? ((note == null ? "" : note + " ") + "[Fewer quotes than policy: "
                + quoteException.trim() + "]").trim() : note;
        return buyerApprove(requestId, new BuyerApproval(description == null || description.isBlank()
                ? (q.description() == null || q.description().isBlank() ? request.getChosenModel() : q.description())
                : description, q.supplierName(), q.quantity(), q.unitPrice(), noteText, substitutionReason,
                confirmations));
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
        if (request.getStatus() != RequestStatus.SOURCING) {
            throw new IllegalStateException("Request " + request.getNumber() + " is not being sourced.");
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
        String sourcingNote = "Sourced by buyer: " + description + " from " + supplierName
                + (substituted ? " (instead of " + chosen + ": " + reason + ")" : "");
        request.addNote(now, sourcingNote);
        request = repository.save(request);
        // the price is known now: the spend goes through the approval matrix before anything is ordered
        events.publishEvent(new Events.RequestSourced(request.getId(), now));
        return get(request.getId());
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
        List<PurchaseRequest> list = new ArrayList<>(repository.findByStatusOrderBySubmittedAtAsc(RequestStatus.BUYER_REVIEW));
        list.addAll(repository.findByStatusOrderBySubmittedAtAsc(RequestStatus.SOURCING));
        return list;
    }

    public List<PurchaseRequest> inStrategicReview() {
        return repository.findByStatusOrderBySubmittedAtAsc(RequestStatus.STRATEGIC_REVIEW);
    }

    public PurchaseRequest markPooled(String requestId) {
        return transition(requestId, RequestStatus.POOLED, "Lines added to the demand pool");
    }

    public PurchaseRequest markOrdered(String requestId, List<String> orderNumbers, BigDecimal amount) {
        PurchaseRequest request = transition(requestId, RequestStatus.ORDERED,
                "On purchase order(s) " + String.join(", ", orderNumbers));
        events.publishEvent(new Events.RequestOrdered(requestId, request.getNumber(), request.chargeDepartment(),
                orderNumbers, amount, Instant.now(clock)));
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
