package com.compras.procurement.shared;

import java.time.Instant;
import java.util.List;

/** All domain events of the request lifecycle, kept together so the flow is readable in one place. */
public final class Events {

    private Events() {
    }

    /** An employee confirmed a structured request; it now waits for approval. */
    public record RequestSubmitted(String requestId, String requestNumber, boolean nonStandard,
                                   Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** The approval step accepted the request. */
    public record RequestApproved(String requestId, boolean urgent, String note, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** The request lines entered the demand pool, waiting to be consolidated. */
    public record DemandPooled(String requestId, List<String> demandLineIds, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** A consolidated purchase order was released to the supplier. */
    public record OrderReleased(String orderId, String orderNumber, String sku, int quantity,
                                List<String> reasons, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return orderId; }
    }

    /** All lines of a request are on purchase orders; {@code amount} is what the request actually costs. */
    public record RequestOrdered(String requestId, String requestNumber, String department, List<String> orderNumbers,
                                 java.math.BigDecimal amount, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** The employee chose an existing company resource instead of a purchase. */
    public record InternalUseConfirmed(String conversationId, String resourceId, String resourceName,
                                       java.math.BigDecimal avoidedSpend, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return conversationId; }
    }

    /** The assistant could not complete the request and passed it to a human buyer. */
    public record IntakeHandedOff(String conversationId, String reason, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return conversationId; }
    }

    /** The requester withdrew the request before it was ordered. */
    public record RequestCancelled(String requestId, String requestNumber, String by, String reason,
                                   Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** A buyer priced a special request; the spend now goes through approval. */
    public record RequestSourced(String requestId, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** An approval step is waiting for someone. */
    public record ApprovalRequested(String requestId, String requestNumber, String approverName, String role,
                                    String reason, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** Someone approved or rejected their step. */
    public record ApprovalDecided(String requestId, String requestNumber, String approverName, String role,
                                  boolean approved, String note, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** A buyer or approver rejected a request; the employee sees the reason in "My requests". */
    public record RequestRejected(String requestId, String requestNumber, String reason, Instant occurredAt)
            implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** Policy 7: a purchase went to the category manager (AUTO trigger or MANUAL escalation). */
    public record StrategicReviewRequested(String requestId, String requestNumber, String source, List<String> reasons,
                                           String raisedBy, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** The category manager decided how a strategic purchase is sourced. */
    public record StrategicDecided(String requestId, String requestNumber, String outcome, String decidedBy,
                                   String note, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }
}
