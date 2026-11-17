package com.compras.procurement.request;

import java.util.Set;

/**
 * Lifecycle of a purchase request. Transitions are explicit, so an invalid jump
 * (e.g. ordering a rejected request) fails loudly instead of corrupting data.
 */
public enum RequestStatus {
    SUBMITTED,
    /** Waiting for one or more people in the approval chain (manager, finance, IT...). */
    PENDING_APPROVAL,
    /** Non-standard request: a buyer has to source and price it before approval. */
    BUYER_REVIEW,
    /** A buyer (or the category manager) accepted it and is collecting quotes. */
    SOURCING,
    /** Strategic (policy 7): the category manager decides how it is sourced. */
    STRATEGIC_REVIEW,
    /** Strategic review found an existing agreement covers it: the employee requests it there. */
    REDIRECTED,
    APPROVED,
    REJECTED,
    POOLED,
    ORDERED,
    /** Everything ordered for it was delivered and checked in (assets registered where the policy says so). */
    RECEIVED,
    CANCELLED,
    CLOSED;

    public Set<RequestStatus> next() {
        return switch (this) {
            case SUBMITTED -> Set.of(PENDING_APPROVAL, APPROVED, BUYER_REVIEW, SOURCING, STRATEGIC_REVIEW, REJECTED, CANCELLED);
            // special requests: the need is approved, then a buyer sources it; an approver may escalate
            case PENDING_APPROVAL -> Set.of(APPROVED, BUYER_REVIEW, SOURCING, STRATEGIC_REVIEW, REJECTED, CANCELLED);
            case BUYER_REVIEW -> Set.of(SOURCING, STRATEGIC_REVIEW, REJECTED, CANCELLED);
            // the award goes to approval for the real amount (or straight through if no one else must approve)
            case SOURCING -> Set.of(PENDING_APPROVAL, APPROVED, STRATEGIC_REVIEW, REJECTED, CANCELLED);
            case STRATEGIC_REVIEW -> Set.of(SOURCING, BUYER_REVIEW, PENDING_APPROVAL, APPROVED, REDIRECTED, REJECTED, CANCELLED);
            case APPROVED -> Set.of(POOLED, ORDERED, CANCELLED); // ORDERED directly: special requests skip the pool
            case POOLED -> Set.of(ORDERED, CANCELLED);
            // SOURCING: a wrong special order is cancelled before delivery and sourced again
            case ORDERED -> Set.of(RECEIVED, SOURCING, CLOSED);
            case RECEIVED -> Set.of(CLOSED);
            case REJECTED, CANCELLED, CLOSED, REDIRECTED -> Set.of();
        };
    }

    public boolean canMoveTo(RequestStatus target) {
        return next().contains(target);
    }
}
