package com.compras.procurement.budget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test double with the same atomicity as the Mongo store (each operation is one synchronized step). */
public class InMemoryBudgetStore implements BudgetStore {

    private final Map<String, BudgetAccount> accounts = new LinkedHashMap<>();
    private final Map<String, BudgetEntry> entries = new LinkedHashMap<>();

    @Override
    public synchronized Optional<BudgetAccount> account(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public synchronized void ensure(BudgetAccount account) {
        accounts.putIfAbsent(account.id(), account);
    }

    @Override
    public synchronized boolean tryReserve(String accountId, long amount) {
        BudgetAccount a = accounts.get(accountId);
        if (a.available() < amount) {
            return false;
        }
        move(accountId, -amount, amount, 0, 0);
        return true;
    }

    @Override
    public synchronized void move(String accountId, long available, long reserved, long committed, long spent) {
        BudgetAccount a = accounts.get(accountId);
        accounts.put(accountId, new BudgetAccount(a.id(), a.department(), a.costCenter(), a.period(), a.allocated(),
                a.available() + available, a.reserved() + reserved, a.committed() + committed, a.spent() + spent));
    }

    @Override
    public synchronized boolean insert(BudgetEntry entry) {
        return entries.putIfAbsent(entry.id(), entry) == null;
    }

    @Override
    public synchronized void delete(String entryId) {
        entries.remove(entryId);
    }

    @Override
    public synchronized List<BudgetEntry> entriesForRequest(String requestId) {
        return entries.values().stream().filter(e -> e.requestId().equals(requestId))
                .sorted(Comparator.comparing(BudgetEntry::at)).toList();
    }

    @Override
    public synchronized List<BudgetEntry> entriesForAccount(String accountId) {
        return entries.values().stream().filter(e -> e.accountId().equals(accountId)).toList();
    }

    @Override
    public synchronized List<BudgetAccount> accounts(String period) {
        return new ArrayList<>(accounts.values().stream().filter(a -> a.period().equals(period)).toList());
    }
}
