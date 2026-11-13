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

    /** All lines of a request are on purchase orders. */
    public record RequestOrdered(String requestId, List<String> orderNumbers, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return requestId; }
    }

    /** The assistant could not complete the request and passed it to a human buyer. */
    public record IntakeHandedOff(String conversationId, String reason, Instant occurredAt) implements DomainEvent {
        public String aggregateId() { return conversationId; }
    }
}
