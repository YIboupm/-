package com.compras.procurement.budget;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Quarterly budget pool.
 *
 * @param allocations  per department, per quarter; their sum is the company's pool
 * @param maxChecksPerConversation how many budget verdicts one intake conversation may obtain: a yes/no answer
 *                     leaks one bit, so repeated checks with different amounts could narrow down the balance
 */
@ConfigurationProperties("procurement.budget")
public record BudgetProperties(Map<String, BigDecimal> allocations, int maxChecksPerConversation) {

    public BudgetProperties {
        allocations = allocations == null ? Map.of() : allocations;
        maxChecksPerConversation = maxChecksPerConversation <= 0 ? 3 : maxChecksPerConversation;
    }

    public BigDecimal pool() {
        return allocations.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
