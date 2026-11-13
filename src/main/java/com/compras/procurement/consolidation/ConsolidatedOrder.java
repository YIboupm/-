package com.compras.procurement.consolidation;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A purchase order that merges demand from several requests. Every allocation points back to
 * the original request line, so goods can be distributed and costs charged to the right cost
 * centre when they arrive.
 */
@Document("consolidated_orders")
public record ConsolidatedOrder(
        @Id String id,
        String number,
        String sku,
        String itemName,
        String supplierId,
        String supplierName,
        String deliverySite,
        int totalQuantity,
        BigDecimal unitPrice,
        BigDecimal listUnitPrice,
        BigDecimal totalAmount,
        BigDecimal individualTotal,
        BigDecimal savings,
        String currency,
        List<ReleaseReason> reasons,
        List<Allocation> allocations,
        LocalDate releaseDate,
        Instant createdAt
) {
    /**
     * @param individualUnitPrice price this request would have paid if ordered on its own
     * @param daysWaited days between entering the pool and being ordered
     */
    public record Allocation(String demandLineId, String requestId, String requestNumber, String requesterName,
                             String department, int quantity, LocalDate neededBy,
                             BigDecimal individualUnitPrice, long daysWaited) {
    }
}
