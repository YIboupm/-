package com.compras.procurement.request;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the buyer awarded for one line of a special request: which quote, at what price, checked against what.
 *
 * @param deviationPercent how far the unit price is from the line's reference price (null without a reference)
 * @param priceNote        the buyer's explanation when the deviation is over the policy's limit
 */
public record Award(String itemId, String itemName, int quoteIndex, String description, String supplierName,
                    int quantity, BigDecimal unitPrice, BigDecimal total, String reference, Boolean substituted,
                    String substitutionReason, List<BuyerApproval.Confirmation> confirmations, String quoteException,
                    BigDecimal referenceUnitPrice, BigDecimal deviationPercent, String priceNote) {
}
