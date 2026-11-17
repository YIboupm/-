package com.compras.procurement.budget;
import java.util.List;
import java.util.Optional;
/** Balance and journal are committed in one versioned account document. */
public interface BudgetStore {
    Optional<BudgetAccount> account(String id);
    void ensure(BudgetAccount account);
    boolean compareAndSet(BudgetAccount expected, BudgetAccount updated);
    List<BudgetEntry> entriesForRequest(String requestId);
    List<BudgetEntry> entriesForAccount(String accountId);
    List<BudgetAccount> accounts(String period);
}
