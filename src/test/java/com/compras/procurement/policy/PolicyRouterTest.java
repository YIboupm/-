package com.compras.procurement.policy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRouterTest {

    private final PolicyRouter router = new PolicyRouter(PolicyFiles::builtin);

    private RouteDecision route(boolean special, boolean framework, String value, Map<String, Object> attrs) {
        return router.route(new PolicyRouter.Input(special, framework, new BigDecimal(value), attrs));
    }

    @Test
    void catalogItemsAreStandardUntilTheStrategicValue() {
        assertEquals("STANDARD", route(false, false, "24000", Map.of()).route());
        RouteDecision big = route(false, false, "30000", Map.of());
        assertEquals("STRATEGIC", big.route());
        assertTrue(big.reasons().get(0).contains("policy 7"));
    }

    @Test
    void specialRequestsGoToABuyerUnlessAStrategicTriggerApplies() {
        assertEquals("BUYER_SOURCED", route(true, false, "0", Map.of()).route());
        assertEquals("BUYER_SOURCED", route(true, false, "0", Map.of("termMonths", 12, "dataClassification", "Confidential")).route());
        assertEquals("STRATEGIC", route(true, true, "0", Map.of()).route());                           // outside the framework
        assertEquals("STRATEGIC", route(true, false, "0", Map.of("termMonths", 24)).route());          // long term
        assertEquals("STRATEGIC", route(true, false, "0", Map.of("soleSource", true)).route());       // sole source
        assertEquals("STRATEGIC", route(true, false, "0", Map.of("dataClassification", "Restricted")).route());
        assertEquals(4, route(true, true, "30000", Map.of("termMonths", 36, "soleSource", "true")).reasons().size());
    }

    @Test
    void quotesFollowTheValueBands() {
        assertEquals(1, router.quotesRequired(new BigDecimal("3000")));
        assertEquals(3, router.quotesRequired(new BigDecimal("3000.01")));
        assertEquals(3, router.quotesRequired(new BigDecimal("24000")));
    }
}
