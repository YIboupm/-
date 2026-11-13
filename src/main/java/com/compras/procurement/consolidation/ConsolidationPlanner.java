package com.compras.procurement.consolidation;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.PriceBreak;
import com.compras.procurement.shared.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure decision logic: groups pooled lines and applies the release policies. No database, no
 * side effects, so every rule can be unit-tested and the result can be shown as a preview.
 */
@Component
public class ConsolidationPlanner {

    private final List<ReleasePolicy> policies;
    private final ConsolidationProperties properties;

    public ConsolidationPlanner(List<ReleasePolicy> policies, ConsolidationProperties properties) {
        this.policies = policies;
        this.properties = properties;
    }

    public List<GroupDecision> plan(List<DemandLine> pooled, Map<String, CatalogItem> itemsBySku, LocalDate asOf) {
        Map<GroupKey, List<DemandLine>> groups = new LinkedHashMap<>();
        pooled.stream()
                .sorted(Comparator.comparing(DemandLine::getSku).thenComparing(DemandLine::getDeliverySite))
                .forEach(l -> groups.computeIfAbsent(l.groupKey(), k -> new ArrayList<>()).add(l));

        List<GroupDecision> decisions = new ArrayList<>();
        groups.forEach((key, lines) -> {
            CatalogItem item = itemsBySku.get(key.sku());
            if (item != null) {
                decisions.add(decide(new PoolGroup(key, item, lines), asOf));
            }
        });
        return decisions;
    }

    /** Urgent lines skip the policies. */
    public GroupDecision urgent(PoolGroup group, LocalDate asOf) {
        return build(group, asOf, List.of(ReleaseReason.URGENT));
    }

    GroupDecision decide(PoolGroup group, LocalDate asOf) {
        List<ReleaseReason> reasons = policies.stream()
                .map(p -> p.evaluate(group, asOf))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        return build(group, asOf, reasons);
    }

    private GroupDecision build(PoolGroup group, LocalDate asOf, List<ReleaseReason> reasons) {
        CatalogItem item = group.item();
        int qty = group.totalQuantity();
        BigDecimal pooledUnit = item.unitPriceFor(qty);
        BigDecimal individual = group.lines().stream()
                .map(l -> Money.times(item.unitPriceFor(l.getQuantity()), l.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pooled = Money.times(pooledUnit, qty);
        Optional<PriceBreak> next = item.nextBreakAbove(qty);
        LocalDate latest = group.latestOrderDate(properties.safetyBufferDays());
        LocalDate nextWindow = asOf.with(TemporalAdjusters.next(properties.windowDay()));

        return new GroupDecision(group.key(), item.name(), item.supplierName(), qty, group.requestCount(),
                group.earliestNeededBy(), latest, nextWindow, item.listPrice(), pooledUnit,
                Money.scale(individual), pooled, Money.scale(individual.subtract(pooled)),
                next.map(PriceBreak::minQty).orElse(null), next.map(PriceBreak::unitPrice).orElse(null),
                reasons, explain(reasons, qty, latest, nextWindow, next), group.lines(), null);
    }

    private static String explain(List<ReleaseReason> reasons, int qty, LocalDate latest, LocalDate nextWindow,
                                  Optional<PriceBreak> next) {
        if (!reasons.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (ReleaseReason r : reasons) {
                parts.add(switch (r) {
                    case URGENT -> "urgent request";
                    case CONSOLIDATION_WINDOW -> "weekly consolidation day";
                    case BEST_PRICE_REACHED -> "best price tier reached";
                    case DEADLINE -> "latest order date " + latest + " reached";
                    case SPECIAL_REQUEST -> "special request sourced by a buyer";
                });
            }
            return "Release now: " + String.join(", ", parts) + ".";
        }
        StringBuilder sb = new StringBuilder("Hold");
        if (latest != null && latest.isBefore(nextWindow)) {
            sb.append(": deadline comes first, the daily run orders it on ").append(latest)
                    .append(" (before the next consolidation on ").append(nextWindow).append(")");
        } else {
            sb.append(": next consolidation on ").append(nextWindow);
            if (latest != null) {
                sb.append(", must be ordered by ").append(latest);
            }
        }
        next.ifPresent(b -> sb.append("; ").append(b.minQty() - qty).append(" more unit(s) reach ")
                .append(b.unitPrice()).append(" per unit"));
        return sb.append('.').toString();
    }
}
