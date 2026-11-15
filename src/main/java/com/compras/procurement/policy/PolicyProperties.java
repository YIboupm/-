package com.compras.procurement.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * The numbers of the procurement policy (sections 2, 5 and 7). Changing the policy = changing these values.
 *
 * @param singleQuoteUpTo      section 5: up to this contract value one quote or a public price list is enough
 * @param strategicAbove       section 5/7: above this contract value the purchase is strategic (category manager)
 * @param maxTermMonths        section 7: a new supplier or service for longer than this is strategic
 * @param splitWindowDays      section 2: requests of one department and category within this window are added up
 */
@ConfigurationProperties("procurement.policy")
public record PolicyProperties(BigDecimal singleQuoteUpTo, BigDecimal strategicAbove, int maxTermMonths,
                               int splitWindowDays) {

    public PolicyProperties {
        singleQuoteUpTo = singleQuoteUpTo == null ? new BigDecimal("3000") : singleQuoteUpTo;
        strategicAbove = strategicAbove == null ? new BigDecimal("25000") : strategicAbove;
        maxTermMonths = maxTermMonths <= 0 ? 12 : maxTermMonths;
        splitWindowDays = splitWindowDays <= 0 ? 30 : splitWindowDays;
    }

    public static PolicyProperties defaults() {
        return new PolicyProperties(null, null, 0, 0);
    }
}
