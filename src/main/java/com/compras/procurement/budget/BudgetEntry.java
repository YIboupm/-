package com.compras.procurement.budget;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One movement of money in the budget ledger. The id is the idempotency key ("requestId:RESERVE"), so a retried
 * or double-clicked step can never move money twice. Amounts in cents; positive = moved into that bucket.
 */
@Document("budget_ledger")
public record BudgetEntry(
        @Id String id,
        @Indexed String accountId,
        @Indexed String requestId,
        String requestNumber,
        Type type,
        long amount,
        long released,
        boolean overBudget,
        String actor,
        String note,
        Instant at
) {
    public enum Type {
        /** Approved: the estimate is held. */
        RESERVE,
        /** Ordered: the reservation becomes a commitment at the real price; the difference is released. */
        COMMIT,
        /** Rejected or cancelled: whatever was held goes back. */
        RELEASE
    }
}
