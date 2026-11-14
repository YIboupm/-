package com.compras.procurement.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a buyer decides for a special request.
 *
 * @param substitutionReason required when the item differs from the model the employee chose
 * @param confirmations      the buyer's yes/no per employee requirement, for the item actually bought
 */
public record BuyerApproval(String description, String supplierName, int quantity, BigDecimal unitPrice,
                            String note, String substitutionReason, List<Confirmation> confirmations) {

    /** The buyer's answer to "does the item you are buying meet this requirement?". */
    public record Confirmation(String requirement, boolean met) {
    }
}
