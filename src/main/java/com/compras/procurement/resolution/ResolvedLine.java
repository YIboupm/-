package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.shared.Money;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A concrete catalog line derived from the request fields, priced as if bought on its own
 * (the consolidation module later shows how much merging saves against this price).
 */
public record ResolvedLine(
        String sku,
        String name,
        String categoryCode,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        String supplierId,
        String supplierName,
        int leadTimeDays,
        Map<String, String> specs,
        String note
) {
    public static ResolvedLine of(CatalogItem item, int quantity, String note) {
        BigDecimal unit = item.unitPriceFor(quantity);
        return new ResolvedLine(item.sku(), item.name(), item.categoryCode(), quantity, unit,
                Money.times(unit, quantity), item.supplierId(), item.supplierName(), item.leadTimeDays(),
                item.specs(), note);
    }
}
