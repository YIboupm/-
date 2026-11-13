package com.compras.procurement.consolidation;

/** Lines can only be merged when they are the same item, from the same supplier, to the same place. */
public record GroupKey(String sku, String supplierId, String deliverySite) {
}
