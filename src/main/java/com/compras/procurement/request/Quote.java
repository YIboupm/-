package com.compras.procurement.request;

import java.math.BigDecimal;
import java.time.Instant;

/** One supplier quote kept on the request (policy 5: quotes stay with the request for audit). */
public record Quote(String supplierName, String description, int quantity, BigDecimal unitPrice, BigDecimal total,
                    String reference, String note, String addedBy, Instant addedAt) {
}
