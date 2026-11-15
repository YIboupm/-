package com.compras.procurement.budget;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One department's budget for one quarter, in cents (integer arithmetic, atomic $inc in MongoDB).
 * Invariant: available = allocated - reserved - committed - spent. {@code available} is stored so that
 * "reserve if enough is left" is one atomic conditional update.
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
        long spent
) {
    public static String idOf(String costCenter, String period) {
        return costCenter + ":" + period;
    }
}
