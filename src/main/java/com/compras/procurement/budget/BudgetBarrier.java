package com.compras.procurement.budget;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/** The only implementation of {@link BudgetGate}: a verdict, never a number. */
@Component
public class BudgetBarrier implements BudgetGate {

    private final BudgetService budgets;

    public BudgetBarrier(BudgetService budgets) {
        this.budgets = budgets;
    }

    @Override
    public BudgetVerdict verdict(String department, BigDecimal amount) {
        if (department == null || amount == null || amount.signum() <= 0) {
            return BudgetVerdict.NOT_DISCLOSED;
        }
        return budgets.verdict(department, amount);
    }
}
