package com.compras.procurement.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A standard, quantifiable item the company buys. {@code specs} is free-form on purpose:
 * a laptop and a toner cartridge have nothing in common, which is why this lives in MongoDB.
 *
 * @param suitableFor plain-language uses, given to the model so it can map vague needs to a tier
 * @param attributes normalised, comparable properties (os, ramGb, gpuType ...). Requirements are checked
 *                   against these by code, not by the model; {@code specs} is the human-readable version
 * @param compatibleWith asset models this item fits (used by ASSET_COMPATIBILITY categories)
 */
@Document("catalog_items")
public record CatalogItem(
        @Id String sku,
        String categoryCode,
        String name,
        String description,
        List<String> suitableFor,
        Map<String, String> specs,
        Map<String, Object> attributes,
        String supplierId,
        String supplierName,
        String currency,
        BigDecimal listPrice,
        List<PriceBreak> priceBreaks,
        int leadTimeDays,
        List<String> compatibleWith
) {

    /** Unit price when {@code quantity} units are ordered at once. */
    public BigDecimal unitPriceFor(int quantity) {
        return breaks().stream()
                .filter(b -> quantity >= b.minQty())
                .max(Comparator.comparingInt(PriceBreak::minQty))
                .map(PriceBreak::unitPrice)
                .orElse(listPrice);
    }

    /** The next volume tier above {@code quantity}, if any. */
    public Optional<PriceBreak> nextBreakAbove(int quantity) {
        return breaks().stream()
                .filter(b -> b.minQty() > quantity)
                .min(Comparator.comparingInt(PriceBreak::minQty));
    }

    /** The quantity at which the best price applies (0 when there are no breaks). */
    public int bestBreakQty() {
        return breaks().stream().mapToInt(PriceBreak::minQty).max().orElse(0);
    }

    private List<PriceBreak> breaks() {
        return priceBreaks == null ? List.of() : priceBreaks;
    }
}
