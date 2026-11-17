package com.compras.procurement.budget;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A movement embedded in BudgetAccount.journal. The annotation also reads the legacy separate collection
 * during upgrade. The id is an idempotency key, checked within the account CAS. Amounts are cents.
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
        RELEASE,
        /**
         * Delivered and checked in: the commitment becomes actual spend. (In a real company this happens when the
         * invoice matches the order and the receipt; the demo has no invoices, so receipt stands in for it.)
         */
        SPEND,
        /**
         * The order was cancelled before delivery (wrong item or price): the commitment is released and the request
         * is sourced again. Later movements of the request start a new cycle with their own keys.
         */
        CANCEL
    }
}
