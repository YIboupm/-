package com.compras.procurement.budget;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for the budget ledger. Every balance change is a single atomic operation on one account, so two
 * approvals racing for the last euros cannot both succeed.
 */
public interface BudgetStore {

    Optional<BudgetAccount> account(String id);

    /** Creates the account if it does not exist yet (no-op otherwise). */
    void ensure(BudgetAccount account);

    /** Atomically: if available >= amount, move amount from available to reserved. False if not enough. */
    boolean tryReserve(String accountId, long amount);

    /** Atomically adds the deltas (no condition). */
    void move(String accountId, long available, long reserved, long committed, long spent);

    /** Inserts an entry; false if an entry with this id (idempotency key) already exists. */
    boolean insert(BudgetEntry entry);

    void delete(String entryId);

    List<BudgetEntry> entriesForRequest(String requestId);

    List<BudgetEntry> entriesForAccount(String accountId);

    List<BudgetAccount> accounts(String period);
}
