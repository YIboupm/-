package com.compras.procurement.request;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One supplier quote kept on the request (policy 5: quotes stay with the request for audit).
 *
 * @param itemId    the line of the request it covers (null on quotes from before requests had lines: the only line)
 * @param withdrawn why the buyer withdrew it (a wrong quote is not deleted: it stays for audit), or null
 */
public record Quote(String supplierName, String description, int quantity, BigDecimal unitPrice, BigDecimal total,
                    String reference, String note, String addedBy, Instant addedAt, String itemId, String withdrawn) {

    public Quote(String supplierName, String description, int quantity, BigDecimal unitPrice, BigDecimal total,
                 String reference, String note, String addedBy, Instant addedAt) {
        this(supplierName, description, quantity, unitPrice, total, reference, note, addedBy, addedAt, null, null);
    }

    public boolean covers(String item, boolean onlyLine) {
        return withdrawn == null && (item.equals(itemId) || (itemId == null && onlyLine));
    }

    public Quote withdraw(String reason) {
        return new Quote(supplierName, description, quantity, unitPrice, total, reference, note, addedBy, addedAt,
                itemId, reason);
    }
}
