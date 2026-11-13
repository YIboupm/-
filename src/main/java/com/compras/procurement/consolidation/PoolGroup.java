package com.compras.procurement.consolidation;

import com.compras.procurement.catalog.CatalogItem;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Pooled lines that could go on the same purchase order. */
public record PoolGroup(GroupKey key, CatalogItem item, List<DemandLine> lines) {

    public int totalQuantity() {
        return lines.stream().mapToInt(DemandLine::getQuantity).sum();
    }

    public long requestCount() {
        return lines.stream().map(DemandLine::getRequestId).distinct().count();
    }

    public LocalDate earliestNeededBy() {
        return lines.stream().map(DemandLine::getNeededBy).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
    }

    /** Last day the order can be placed and still arrive on time: needed-by minus lead time minus buffer. */
    public LocalDate latestOrderDate(int safetyBufferDays) {
        LocalDate earliest = earliestNeededBy();
        return earliest == null ? null : earliest.minusDays((long) item.leadTimeDays() + safetyBufferDays);
    }
}
