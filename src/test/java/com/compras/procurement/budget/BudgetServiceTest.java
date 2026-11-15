package com.compras.procurement.budget;

import com.compras.procurement.context.Department;
import com.compras.procurement.context.EmployeeContextService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BudgetServiceTest {

    private final InMemoryBudgetStore store = new InMemoryBudgetStore();
    private final EmployeeContextService departments = new EmployeeContextService(null, null, null) {
        @Override
        public Optional<Department> department(String name) {
            return Optional.of(new Department(name, "CC-" + name.toUpperCase()));
        }
    };
    private final BudgetService budgets = new BudgetService(store,
            new BudgetProperties(Map.of("Engineering", new BigDecimal("5000")), 3), departments,
            Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneId.of("Europe/Madrid")));

    private BudgetAccount eng() {
        return budgets.account("Engineering");
    }

    @Test
    void reserveCommitReleaseKeepTheBalanceConsistent() {
        assertEquals("2026-Q4", budgets.currentPeriod());
        assertEquals(BudgetService.Outcome.RESERVED,
                budgets.reserve("r1", "PR-1", "Engineering", new BigDecimal("2000"), false, "Elena"));
        assertEquals(300_000, eng().available());
        assertEquals(200_000, eng().reserved());

        // ordered cheaper thanks to consolidation: the difference goes back
        budgets.commit("r1", "PR-1", "Engineering", new BigDecimal("1800"), "system");
        assertEquals(320_000, eng().available());
        assertEquals(0, eng().reserved());
        assertEquals(180_000, eng().committed());

        budgets.reserve("r2", "PR-2", "Engineering", new BigDecimal("1000"), false, "Elena");
        budgets.release("r2", "PR-2", "Javier", "cancelled");
        assertEquals(320_000, eng().available());
        assertEquals(0, eng().reserved());
        assertEquals(eng().allocated(), eng().available() + eng().reserved() + eng().committed() + eng().spent());
    }

    @Test
    void stepsAreIdempotent() {
        budgets.reserve("r1", "PR-1", "Engineering", new BigDecimal("1000"), false, "a");
        assertEquals(BudgetService.Outcome.ALREADY_RESERVED,
                budgets.reserve("r1", "PR-1", "Engineering", new BigDecimal("1000"), false, "a"));
        budgets.release("r1", "PR-1", "a", "x");
        budgets.release("r1", "PR-1", "a", "x");
        assertEquals(500_000, eng().available());
    }

    @Test
    void overBudgetOnlyWhenFinanceAccepted() {
        assertEquals(BudgetVerdict.OVER_BUDGET, budgets.verdict("Engineering", new BigDecimal("6000")));
        assertEquals(BudgetService.Outcome.INSUFFICIENT,
                budgets.reserve("r1", "PR-1", "Engineering", new BigDecimal("6000"), false, "Elena"));
        assertEquals(500_000, eng().available());
        assertEquals(BudgetService.Outcome.RESERVED_OVER_BUDGET,
                budgets.reserve("r1", "PR-1", "Engineering", new BigDecimal("6000"), true, "Isabel"));
        assertEquals(-100_000, eng().available());
    }

    @Test
    void concurrentApprovalsCannotSpendTheSameEuroTwice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<BudgetService.Outcome> outcomes = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 20; i++) {
            String id = "r" + i;
            pool.submit(() -> {
                start.await();
                outcomes.add(budgets.reserve(id, id, "Engineering", new BigDecimal("1000"), false, "x"));
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
        assertEquals(5, outcomes.stream().filter(o -> o == BudgetService.Outcome.RESERVED).count());
        assertEquals(15, outcomes.stream().filter(o -> o == BudgetService.Outcome.INSUFFICIENT).count());
        assertEquals(0, eng().available());
    }

    @Test
    void theGateGivesAVerdictNeverAFigure() {
        BudgetGate gate = new BudgetBarrier(budgets);
        assertEquals(BudgetVerdict.WITHIN_BUDGET, gate.verdict("Engineering", new BigDecimal("100")));
        assertEquals(BudgetVerdict.NOT_DISCLOSED, gate.verdict("Engineering", null));
        // the interface itself has exactly one method, returning an enum: no figure can cross it
        assertEquals(1, BudgetGate.class.getDeclaredMethods().length);
        assertEquals(BudgetVerdict.class, BudgetGate.class.getDeclaredMethods()[0].getReturnType());
    }
}
