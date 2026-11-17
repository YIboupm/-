package com.compras.procurement.budget;
import java.util.*;
/** Same atomic CAS contract as MongoDB. */
public class InMemoryBudgetStore implements BudgetStore {
    private final Map<String,BudgetAccount> accounts = new LinkedHashMap<>();
    public synchronized Optional<BudgetAccount> account(String id) { return Optional.ofNullable(accounts.get(id)); }
    public synchronized void ensure(BudgetAccount a) { accounts.putIfAbsent(a.id(), a); }
    public synchronized boolean compareAndSet(BudgetAccount before, BudgetAccount after) {
        if (!accounts.get(before.id()).revision().equals(before.revision())) return false;
        accounts.put(after.id(), after); return true;
    }
    public synchronized List<BudgetEntry> entriesForRequest(String id) {
        return accounts.values().stream().flatMap(a -> a.journal().stream()).filter(e -> e.requestId().equals(id)).toList();
    }
    public synchronized List<BudgetEntry> entriesForAccount(String id) { return accounts.get(id).journal(); }
    public synchronized List<BudgetAccount> accounts(String period) {
        return accounts.values().stream().filter(a -> a.period().equals(period)).toList();
    }
}
