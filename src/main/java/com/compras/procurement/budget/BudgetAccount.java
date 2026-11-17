package com.compras.procurement.budget;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One department's budget for one quarter, in cents. Balance and journal are one versioned document.
 * Invariant: available = allocated - reserved - committed - spent. {@code available} is stored so that
 * "reserve if enough is left" and its journal entry are one atomic conditional replacement.
 */
@Document("budget_accounts")
public record BudgetAccount(
        @Id String id,
        String department,
        String costCenter,
        String period,
        long allocated,
        long available,
        long reserved,
        long committed,
        long spent,
        Long revision,
        java.util.List<BudgetEntry> journal
) {
    public BudgetAccount(String id, String department, String costCenter, String period, long allocated,
                         long available, long reserved, long committed, long spent) {
        this(id, department, costCenter, period, allocated, available, reserved, committed, spent, 0L, java.util.List.of());
    }

    public BudgetAccount append(BudgetEntry entry, long availableDelta, long reservedDelta, long committedDelta) {
        var next = new java.util.ArrayList<>(journal);
        next.add(entry);
        return new BudgetAccount(id, department, costCenter, period, allocated, available + availableDelta,
                reserved + reservedDelta, committed + committedDelta, spent, revision + 1, java.util.List.copyOf(next));
    }
    /** Committed money that was delivered: it is now spent. */
    public BudgetAccount appendSpend(BudgetEntry entry, long amount) {
        var next = new java.util.ArrayList<>(journal);
        next.add(entry);
        return new BudgetAccount(id, department, costCenter, period, allocated, available, reserved,
                committed - amount, spent + amount, revision + 1, java.util.List.copyOf(next));
    }

    public static String idOf(String costCenter, String period) {
        return costCenter + ":" + period;
    }
}
