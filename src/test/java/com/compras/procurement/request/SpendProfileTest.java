package com.compras.procurement.request;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpendProfileTest {

    @Test
    void aPurchaseIsChargedInFullACloudCallOffOnlyForTheMonthsLeftInTheQuarter() {
        assertEquals(new BigDecimal("2059"), SpendProfile.quarterCharge(Map.of(), new BigDecimal("2059"), LocalDate.of(2026, 11, 15)));
        Map<String, Object> cloud = Map.of("monthlyEstimate", 620, "termMonths", 12);
        // 15 November: November and December are left in Q4
        assertEquals(new BigDecimal("1240.00"), SpendProfile.quarterCharge(cloud, new BigDecimal("7440"), LocalDate.of(2026, 11, 15)));
        // 1 October: the whole quarter
        assertEquals(new BigDecimal("1860.00"), SpendProfile.quarterCharge(cloud, new BigDecimal("7440"), LocalDate.of(2026, 10, 1)));
        // a one-month term never charges more than one month
        assertEquals(new BigDecimal("620.00"), SpendProfile.quarterCharge(Map.of("monthlyEstimate", 620, "termMonths", 1),
                new BigDecimal("620"), LocalDate.of(2026, 10, 1)));
    }
}
