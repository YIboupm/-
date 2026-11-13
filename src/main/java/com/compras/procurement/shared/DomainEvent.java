package com.compras.procurement.shared;

import java.time.Instant;

/**
 * Marker for everything that happens in the domain. Modules publish events instead of calling
 * each other directly, so new consumers (accounting, notifications, analytics) can subscribe
 * without changing the publisher.
 */
public interface DomainEvent {

    /** Id of the business object the event is about (request id, order id ...). */
    String aggregateId();

    Instant occurredAt();

    default String type() {
        return getClass().getSimpleName();
    }
}
