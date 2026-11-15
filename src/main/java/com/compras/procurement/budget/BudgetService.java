package com.compras.procurement.budget;

import com.compras.procurement.context.EmployeeContextService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The quarterly budget pool and its ledger: reserve on approval, commit on order (difference released),
 * release on rejection or cancellation. Finance-internal: requester-facing code uses {@link BudgetGate}.
 */
@Service
public class BudgetService {

    public enum Outcome { RESERVED, RESERVED_OVER_BUDGET, INSUFFICIENT, ALREADY_RESERVED }

    /** What one request currently holds, in cents. */
    public record Position(long reserved, long committed) {
    }

    private final BudgetStore store;
    private final BudgetProperties properties;
    private final EmployeeContextService employees;
    private final Clock clock;

    public BudgetService(BudgetStore store, BudgetProperties properties, EmployeeContextService employees,
                         Clock clock) {
        this.store = store;
        this.properties = properties;
        this.employees = employees;
        this.clock = clock;
    }

    public String currentPeriod() {
        LocalDate d = LocalDate.now(clock);
        return d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
    }

    /** All departments' accounts for the current quarter (created from the allocations on first use). */
    public List<BudgetAccount> accounts() {
        properties.allocations().keySet().forEach(this::accountFor);
        return store.accounts(currentPeriod());
    }

    public BudgetAccount account(String department) {
        return accountFor(department);
    }

    public List<BudgetEntry> ledger(String department) {
        return store.entriesForAccount(accountFor(department).id());
    }

    public BudgetVerdict verdict(String department, BigDecimal amount) {
        return accountFor(department).available() >= cents(amount)
                ? BudgetVerdict.WITHIN_BUDGET : BudgetVerdict.OVER_BUDGET;
    }

    /**
     * Holds the approved estimate. Atomic against concurrent approvals: only one of two requests competing for
     * the last euros gets {@link Outcome#RESERVED}; the other gets {@link Outcome#INSUFFICIENT} unless finance has
     * already accepted going over budget ({@code allowOverBudget}).
     */
    public Outcome reserve(String requestId, String requestNumber, String department, BigDecimal amount,
                           boolean allowOverBudget, String actor) {
        String key = requestId + ":" + BudgetEntry.Type.RESERVE;
        if (!store.entriesForRequest(requestId).stream().filter(e -> e.id().equals(key)).toList().isEmpty()) {
            return Outcome.ALREADY_RESERVED;
        }
        BudgetAccount account = accountFor(department);
        long amt = cents(amount);
        boolean fits = store.tryReserve(account.id(), amt);
        if (!fits) {
            if (!allowOverBudget) {
                return Outcome.INSUFFICIENT;
            }
            store.move(account.id(), -amt, amt, 0, 0);
        }
        BudgetEntry entry = new BudgetEntry(key, account.id(), requestId, requestNumber, BudgetEntry.Type.RESERVE,
                amt, 0, !fits, actor, fits ? "Approved estimate held" : "Held over budget (approved by finance)",
                Instant.now(clock));
        if (!store.insert(entry)) {
            store.move(account.id(), amt, -amt, 0, 0); // a concurrent duplicate won: undo
            return Outcome.ALREADY_RESERVED;
        }
        return fits ? Outcome.RESERVED : Outcome.RESERVED_OVER_BUDGET;
    }

    /** Ordered at the real price: the reservation becomes a commitment and the difference goes back. */
    public void commit(String requestId, String requestNumber, String department, BigDecimal actual, String actor) {
        Position held = position(requestId);
        String accountId = accountOf(requestId, department);
        long amt = cents(actual);
        long returned = held.reserved() - amt;
        BudgetEntry entry = new BudgetEntry(requestId + ":" + BudgetEntry.Type.COMMIT, accountId, requestId,
                requestNumber, BudgetEntry.Type.COMMIT, amt, returned, false, actor,
                returned > 0 ? "Ordered below the estimate: difference returned"
                        : returned < 0 ? "Ordered above the estimate: difference charged" : "Ordered at the estimate",
                Instant.now(clock));
        if (store.insert(entry)) {
            store.move(accountId, held.reserved() - amt, -held.reserved(), amt, 0);
        }
    }

    /** Rejected or cancelled: everything still held is released. */
    public void release(String requestId, String requestNumber, String actor, String note) {
        Position held = position(requestId);
        if (held.reserved() == 0) {
            return;
        }
        String accountId = accountOf(requestId, null);
        BudgetEntry entry = new BudgetEntry(requestId + ":" + BudgetEntry.Type.RELEASE, accountId, requestId,
                requestNumber, BudgetEntry.Type.RELEASE, held.reserved(), held.reserved(), false, actor, note,
                Instant.now(clock));
        if (store.insert(entry)) {
            store.move(accountId, held.reserved(), -held.reserved(), 0, 0);
        }
    }

    public Position position(String requestId) {
        long reserved = 0;
        long committed = 0;
        for (BudgetEntry e : store.entriesForRequest(requestId)) {
            switch (e.type()) {
                case RESERVE -> reserved += e.amount();
                case COMMIT -> {
                    reserved = 0;
                    committed += e.amount();
                }
                case RELEASE -> reserved = 0;
            }
        }
        return new Position(reserved, committed);
    }

    public BigDecimal pool() {
        return properties.pool();
    }

    public static long cents(BigDecimal amount) {
        return amount == null ? 0 : amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static BigDecimal euros(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }

    // ------------------------------------------------------------------ internals

    private String accountOf(String requestId, String department) {
        return store.entriesForRequest(requestId).stream().map(BudgetEntry::accountId).findFirst()
                .orElseGet(() -> accountFor(department).id());
    }

    private BudgetAccount accountFor(String department) {
        if (department == null) {
            throw new IllegalArgumentException("No department to charge");
        }
        Map.Entry<String, BigDecimal> allocation = properties.allocations().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(department)).findFirst()
                .orElse(Map.entry(department, BigDecimal.ZERO));
        String costCenter = employees.department(allocation.getKey()).map(d -> d.costCenter())
                .orElse("CC-" + allocation.getKey().toUpperCase());
        String id = BudgetAccount.idOf(costCenter, currentPeriod());
        return store.account(id).orElseGet(() -> {
            long amount = cents(allocation.getValue());
            store.ensure(new BudgetAccount(id, allocation.getKey(), costCenter, currentPeriod(), amount, amount, 0, 0, 0));
            return store.account(id).orElseThrow();
        });
    }
}
