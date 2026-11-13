package com.compras.procurement.request;

import java.util.Set;

/**
 * Lifecycle of a purchase request. Transitions are explicit, so an invalid jump
 * (e.g. ordering a rejected request) fails loudly instead of corrupting data.
 */
public enum RequestStatus {
    SUBMITTED,
    /** Non-standard request: a buyer has to source and price it before approval. */
    BUYER_REVIEW,
    APPROVED,
    REJECTED,
    POOLED,
    ORDERED,
    CANCELLED,
    CLOSED;

    public Set<RequestStatus> next() {
        return switch (this) {
            case SUBMITTED -> Set.of(APPROVED, BUYER_REVIEW, REJECTED, CANCELLED);
            case BUYER_REVIEW -> Set.of(APPROVED, REJECTED, CANCELLED);
            case APPROVED -> Set.of(POOLED, ORDERED, CANCELLED); // ORDERED directly: special requests skip the pool
            case POOLED -> Set.of(ORDERED, CANCELLED);
            case ORDERED -> Set.of(CLOSED);
            case REJECTED, CANCELLED, CLOSED -> Set.of();
        };
    }

    public boolean canMoveTo(RequestStatus target) {
        return next().contains(target);
    }
}
