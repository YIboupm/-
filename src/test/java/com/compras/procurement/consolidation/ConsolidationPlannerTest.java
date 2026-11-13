package com.compras.procurement.consolidation;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.PriceBreak;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The release rules, tested without Spring or MongoDB. */
class ConsolidationPlannerTest {

    // Friday 13 Nov 2026; the consolidation window is Tuesday 17 Nov.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 13);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 11, 17);

    private final ConsolidationProperties props = new ConsolidationProperties(DayOfWeek.TUESDAY, 2, "-");
    private final ConsolidationPlanner planner = new ConsolidationPlanner(List.of(
            new ReleasePolicies.WindowPolicy(props),
            new ReleasePolicies.BestPricePolicy(),
            new ReleasePolicies.DeadlinePolicy(props)), props);

    private final CatalogItem laptop = new CatalogItem("LAP-STD", "LAPTOP", "Standard Office Laptop", "", List.of(),
            Map.of(), Map.of(), "SUP-TECH", "TechDistribucion", "EUR", new BigDecimal("899.00"),
            List.of(new PriceBreak(5, new BigDecimal("849.00")), new PriceBreak(10, new BigDecimal("809.00"))),
            5, List.of());

    private DemandLine line(String request, int qty, LocalDate neededBy) {
        return new DemandLine(request, "PR-" + request, "Someone", "Dept", "LAP-STD", "Standard Office Laptop",
                "SUP-TECH", "Madrid HQ", qty, neededBy, false, Instant.parse("2026-11-13T09:00:00Z"));
    }

    private GroupDecision only(List<DemandLine> lines, LocalDate asOf) {
        List<GroupDecision> decisions = planner.plan(lines, Map.of("LAP-STD", laptop), asOf);
        assertEquals(1, decisions.size());
        return decisions.get(0);
    }

    @Test
    void holdsSmallDemandBeforeTheWindow() {
        GroupDecision d = only(List.of(line("1", 2, FRIDAY.plusDays(30))), FRIDAY);
        assertTrue(d.reasons().isEmpty());
        assertTrue(d.explanation().startsWith("Hold"));
        assertEquals(3, d.nextBreakQty() - d.totalQuantity());
    }

    @Test
    void releasesOnTheWeeklyWindow() {
        GroupDecision d = only(List.of(line("1", 2, FRIDAY.plusDays(30))), TUESDAY);
        assertEquals(List.of(ReleaseReason.CONSOLIDATION_WINDOW), d.reasons());
    }

    @Test
    void releasesEarlyWhenTheBestPriceTierIsReached() {
        GroupDecision d = only(List.of(line("1", 6, FRIDAY.plusDays(30)), line("2", 4, FRIDAY.plusDays(30))), FRIDAY);
        assertEquals(List.of(ReleaseReason.BEST_PRICE_REACHED), d.reasons());
    }

    @Test
    void releasesOnTheLatestOrderDateSoNobodyIsLate() {
        // needed in 7 days, lead time 5 + buffer 2 -> must order today
        GroupDecision d = only(List.of(line("1", 1, FRIDAY.plusDays(7))), FRIDAY);
        assertEquals(FRIDAY, d.latestOrderDate());
        assertTrue(d.reasons().contains(ReleaseReason.DEADLINE));
    }

    @Test
    void poolingTwoRequestsReachesTheVolumePrice() {
        GroupDecision d = only(List.of(line("1", 2, FRIDAY.plusDays(30)), line("2", 3, FRIDAY.plusDays(30))), FRIDAY);
        assertEquals(5, d.totalQuantity());
        assertEquals(new BigDecimal("849.00"), d.pooledUnitPrice());
        assertEquals(new BigDecimal("4495.00"), d.individualTotal()); // 5 x 899 when ordered separately
        assertEquals(new BigDecimal("250.00"), d.savings());          // 5 x (899 - 849)
    }

    @Test
    void urgentAlwaysReleases() {
        PoolGroup group = new PoolGroup(new GroupKey("LAP-STD", "SUP-TECH", "Madrid HQ"), laptop,
                List.of(line("1", 1, FRIDAY.plusDays(30))));
        assertEquals(List.of(ReleaseReason.URGENT), planner.urgent(group, FRIDAY).reasons());
    }
}
