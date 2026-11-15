package com.compras.procurement.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * How a request's money falls into budget periods. A one-off purchase is charged in full; a recurring call-off
 * (cloud: monthly estimate × term) charges the current quarter only for the months left in it. The approval
 * matrix always looks at the whole contract value.
 */
public final class SpendProfile {

    private SpendProfile() {
    }

    public static boolean isRecurring(Map<String, Object> attributes) {
        return attributes != null && attributes.get("monthlyEstimate") != null && attributes.get("termMonths") != null;
    }

    /** What the current quarter's budget carries for this request. */
    public static BigDecimal quarterCharge(Map<String, Object> attributes, BigDecimal total, LocalDate today) {
        if (!isRecurring(attributes)) {
            return total;
        }
        BigDecimal monthly = new BigDecimal(String.valueOf(attributes.get("monthlyEstimate")));
        int term = Integer.parseInt(String.valueOf(attributes.get("termMonths")));
        int monthsLeft = 3 - (today.getMonthValue() - 1) % 3; // this month included
        return monthly.multiply(BigDecimal.valueOf(Math.min(term, monthsLeft))).setScale(2);
    }

    public static BigDecimal quarterCharge(PurchaseRequest r, LocalDate today) {
        return quarterCharge(r.getAttributes(), r.amount(), today);
    }
}
