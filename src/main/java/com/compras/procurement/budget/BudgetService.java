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
 * release on rejection or cancellation, spend on delivery. Finance-internal: requester-facing code uses {@link BudgetGate}.
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
        String accountId = accountFor(department).id();
        long amt = cents(amount);
        if (amt < 0) throw new IllegalArgumentException("Negative reservation");
        String key = key(requestId, cycleOf(requestId), BudgetEntry.Type.RESERVE);
        for (int retry = 0; retry < 100; retry++) {
            BudgetAccount account = store.account(accountId).orElseThrow();
            if (account.journal().stream().anyMatch(e -> e.id().equals(key))) return Outcome.ALREADY_RESERVED;
            boolean fits = account.available() >= amt;
            if (!fits && !allowOverBudget) return Outcome.INSUFFICIENT;
            BudgetEntry entry = new BudgetEntry(key, accountId, requestId, requestNumber, BudgetEntry.Type.RESERVE,
                    amt, 0, !fits, actor, fits ? "Approved estimate held" : "Held over budget (approved by finance)",
                    Instant.now(clock));
            if (store.compareAndSet(account, account.append(entry, -amt, amt, 0)))
                return fits ? Outcome.RESERVED : Outcome.RESERVED_OVER_BUDGET;
        }
        throw new IllegalStateException("Budget contention: retry the operation");
    }

    public void commit(String requestId, String requestNumber, String department, BigDecimal actual, String actor) {
        finish(requestId, requestNumber, accountOf(requestId, department), cents(actual), actor, null, true);
    }

    public void release(String requestId, String requestNumber, String actor, String note) {
        var entries = store.entriesForRequest(requestId);
        if (entries.isEmpty()) return;
        finish(requestId, requestNumber, accountOf(requestId, null), 0, actor, note, false);
    }

    /** A request's reservation has exactly one terminal movement: COMMIT or RELEASE. */
    private void finish(String requestId, String number, String accountId, long amount,
                        String actor, String note, boolean commit) {
        if (amount < 0) throw new IllegalArgumentException("Negative commitment");
        int c = cycleOf(requestId);
        for (int retry = 0; retry < 100; retry++) {
            BudgetAccount account = store.account(accountId).orElseThrow();
            var entries = current(account, requestId, c);
            if (entries.stream().anyMatch(e -> e.type() != BudgetEntry.Type.RESERVE)) return;
            var reservation = entries.stream().filter(e -> e.type() == BudgetEntry.Type.RESERVE).findFirst();
            if (reservation.isEmpty()) {
                if (commit) throw new IllegalStateException("Cannot commit without a reservation");
                return;
            }
            long held = reservation.get().amount();
            var type = commit ? BudgetEntry.Type.COMMIT : BudgetEntry.Type.RELEASE;
            BudgetEntry entry = new BudgetEntry(key(requestId, c, type), accountId, requestId, number, type,
                    commit ? amount : held, held - amount, false, actor,
                    commit ? "Reservation committed; price difference returned or charged" : note, Instant.now(clock));
            if (store.compareAndSet(account, account.append(entry, held - amount, -held, amount))) return;
        }
        throw new IllegalStateException("Budget contention: retry the operation");
    }

    /** Delivered: what was committed is now spent. Idempotent; nothing happens without a commitment. */
    public void spend(String requestId, String requestNumber, String actor) {
        settleDelivery(requestId, requestNumber, actor, true);
    }

    public void awaitReplacement(String requestId, String requestNumber, String actor) {
        settleDelivery(requestId, requestNumber, actor, false);
    }

    private void settleDelivery(String requestId, String requestNumber, String actor, boolean complete) {
        var entries = store.entriesForRequest(requestId);
        if (entries.isEmpty()) return;
        String accountId = accountOf(requestId, null);
        int c = cycleOf(requestId);
        for (int retry = 0; retry < 100; retry++) {
            BudgetAccount account = store.account(accountId).orElseThrow();
            var mine = current(account, requestId, c);
            long spent = netSpent(mine);
            var commit = mine.stream().filter(e -> e.type() == BudgetEntry.Type.COMMIT).findFirst();
            if (commit.isEmpty()) return;
            long amount = (complete ? commit.get().amount() : 0) - spent;
            if (amount == 0) return;
            var type = complete ? BudgetEntry.Type.SPEND : BudgetEntry.Type.RETURN_TO_COMMITMENT;
            long previous = mine.stream().filter(e -> e.type() == type).count();
            String movement = key(requestId, c, type) + (previous == 0 ? "" : "#" + previous);
            BudgetEntry entry = new BudgetEntry(movement, accountId, requestId,
                    requestNumber, type, Math.abs(amount), 0, false, actor, complete
                    ? "All goods received" : "Goods returned; awaiting replacement on original order", Instant.now(clock));
            if (store.compareAndSet(account, account.appendSpend(entry, amount))) return;
        }
        throw new IllegalStateException("Budget contention: retry the operation");
    }

    /**
     * The order was cancelled before delivery: its commitment goes back to the department, and the request's next
     * reservation starts a new cycle. Returns the cents released (0 when nothing was committed).
     */
    public long cancelCommitment(String requestId, String requestNumber, String actor, String reason) {
        var entries = store.entriesForRequest(requestId);
        if (entries.isEmpty()) return 0;
        String accountId = accountOf(requestId, null);
        int c = cycleOf(requestId);
        for (int retry = 0; retry < 100; retry++) {
            BudgetAccount account = store.account(accountId).orElseThrow();
            var mine = current(account, requestId, c);
            if (mine.stream().anyMatch(e -> e.type() == BudgetEntry.Type.SPEND)) {
                throw new IllegalStateException("The goods were delivered: the spend cannot be cancelled.");
            }
            var commit = mine.stream().filter(e -> e.type() == BudgetEntry.Type.COMMIT).findFirst();
            if (commit.isEmpty()) return 0;
            long amount = commit.get().amount();
            BudgetEntry entry = new BudgetEntry(requestId + ":" + (c + 1) + ":" + BudgetEntry.Type.CANCEL, accountId,
                    requestId, requestNumber, BudgetEntry.Type.CANCEL, amount, amount, false, actor,
                    "Order cancelled before delivery: " + reason, Instant.now(clock));
            if (store.compareAndSet(account, account.append(entry, amount, 0, -amount))) return amount;
        }
        throw new IllegalStateException("Budget contention: retry the operation");
    }

    public Position position(String requestId) {
        var all = store.entriesForRequest(requestId);
        if (all.isEmpty()) return new Position(0, 0);
        int c = cycle(all);
        var entries = store.account(accountOf(requestId, null)).map(a -> current(a, requestId, c)).orElse(all);
        long committed = entries.stream().filter(e -> e.type() == BudgetEntry.Type.COMMIT)
                .mapToLong(BudgetEntry::amount).sum()
                - netSpent(entries);
        boolean terminal = entries.stream().anyMatch(e -> e.type() != BudgetEntry.Type.RESERVE);
        long reserved = terminal ? 0 : entries.stream().mapToLong(BudgetEntry::amount).sum();
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

    /** How many times this request's order was cancelled and sourced again: each time starts a new cycle. */
    private static int cycle(List<BudgetEntry> requestEntries) {
        return (int) requestEntries.stream().filter(e -> e.type() == BudgetEntry.Type.CANCEL).count();
    }

    /** Idempotency key of a movement in a cycle (cycle 0 keeps the original keys). */
    private static String key(String requestId, int cycle, BudgetEntry.Type type) {
        return cycle == 0 ? requestId + ":" + type : requestId + ":" + cycle + ":" + type;
    }

    private int cycleOf(String requestId) {
        return cycle(store.entriesForRequest(requestId));
    }

    /** The request's movements in an account, in the given cycle. */
    private static List<BudgetEntry> current(BudgetAccount account, String requestId, int c) {
        List<BudgetEntry> mine = account.journal().stream().filter(e -> e.requestId().equals(requestId)).toList();
        java.util.Set<String> keys = java.util.Arrays.stream(BudgetEntry.Type.values())
                .filter(t -> t != BudgetEntry.Type.CANCEL).map(t -> key(requestId, c, t))
                .collect(java.util.stream.Collectors.toSet());
        return mine.stream().filter(e -> keys.contains(e.id().split("#", 2)[0])).toList();
    }

    private static long netSpent(List<BudgetEntry> entries) {
        return entries.stream().mapToLong(e -> switch (e.type()) {
            case SPEND -> e.amount();
            case RETURN_TO_COMMITMENT -> -e.amount();
            default -> 0;
        }).sum();
    }

    /** The account of the request's latest reservation (a request sourced again may be in a later quarter). */
    private String accountOf(String requestId, String department) {
        var entries = store.entriesForRequest(requestId);
        return entries.stream().filter(e -> e.type() == BudgetEntry.Type.RESERVE)
                .max(java.util.Comparator.comparing(BudgetEntry::at)).map(BudgetEntry::accountId)
                .or(() -> entries.stream().map(BudgetEntry::accountId).findFirst())
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
