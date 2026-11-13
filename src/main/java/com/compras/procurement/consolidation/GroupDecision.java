package com.compras.procurement.consolidation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The planner's verdict on one pooled group, with the numbers that explain it. Shown in the UI so
 * a buyer can see why something was held or released.
 *
 * @param individualTotal what the requests would cost if each were ordered on its own
 * @param pooledTotal what they cost on one order at the pooled quantity's price tier
 */
public record GroupDecision(
        GroupKey key,
        String itemName,
        String supplierName,
        int totalQuantity,
        long requestCount,
        LocalDate earliestNeededBy,
        LocalDate latestOrderDate,
        LocalDate nextWindow,
        BigDecimal listUnitPrice,
        BigDecimal pooledUnitPrice,
        BigDecimal individualTotal,
        BigDecimal pooledTotal,
        BigDecimal savings,
        Integer nextBreakQty,
        BigDecimal nextBreakUnitPrice,
        List<ReleaseReason> reasons,
        String explanation,
        List<DemandLine> lines,
        String orderNumber
) {
    public boolean release() {
        return !reasons.isEmpty();
    }

    public GroupDecision withOrderNumber(String number) {
        return new GroupDecision(key, itemName, supplierName, totalQuantity, requestCount, earliestNeededBy,
                latestOrderDate, nextWindow, listUnitPrice, pooledUnitPrice, individualTotal, pooledTotal, savings,
                nextBreakQty, nextBreakUnitPrice, reasons, explanation, lines, number);
    }
}
