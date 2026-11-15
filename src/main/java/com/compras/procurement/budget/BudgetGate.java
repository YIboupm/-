package com.compras.procurement.budget;

import java.math.BigDecimal;

/**
 * The information barrier between finance and everything a requester or the AI agent can reach.
 *
 * <p>Requester-facing code (the intake agent and its tools) may depend on this interface only. It returns a
 * {@link BudgetVerdict}, never a figure: budgets, balances, reservations and other departments' spending stay
 * inside the budget module. {@code BudgetBarrierTest} fails the build if intake code imports anything else
 * from this package, so the barrier is enforced by the program, not by the prompt.
 */
public interface BudgetGate {

    /** Would this department's remaining budget for the current quarter cover {@code amount}? */
    BudgetVerdict verdict(String department, BigDecimal amount);
}
