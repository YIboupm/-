package com.compras.procurement.policy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyRouterTest {

    private final PolicyRouter router = new PolicyRouter(PolicyProperties.defaults());

    private RouteDecision route(boolean special, boolean framework, String value, Map<String, Object> attrs) {
        return router.route(new PolicyRouter.Input(special, framework, new BigDecimal(value), attrs));
    }

    @Test
    void catalogItemsAreStandardUntilTheStrategicValue() {
        assertEquals(Route.STANDARD, route(false, false, "24000", Map.of()).route());
        RouteDecision big = route(false, false, "30000", Map.of());
        assertEquals(Route.STRATEGIC, big.route());
        assertTrue(big.reasons().get(0).contains("policy 7"));
    }

    @Test
    void specialRequestsGoToABuyerUnlessAStrategicTriggerApplies() {
        assertEquals(Route.BUYER_SOURCED, route(true, false, "0", Map.of()).route());
        assertEquals(Route.BUYER_SOURCED, route(true, false, "0", Map.of("termMonths", 12, "dataClassification", "Confidential")).route());
        assertEquals(Route.STRATEGIC, route(true, true, "0", Map.of()).route());                           // outside the framework
        assertEquals(Route.STRATEGIC, route(true, false, "0", Map.of("termMonths", 24)).route());          // long term
        assertEquals(Route.STRATEGIC, route(true, false, "0", Map.of("soleSource", true)).route());       // sole source
        assertEquals(Route.STRATEGIC, route(true, false, "0", Map.of("dataClassification", "Restricted")).route());
        assertEquals(4, route(true, true, "30000", Map.of("termMonths", 36, "soleSource", "true")).reasons().size());
    }

    @Test
    void quotesFollowTheValueBands() {
        assertEquals(1, router.quotesRequired(new BigDecimal("3000")));
        assertEquals(3, router.quotesRequired(new BigDecimal("3000.01")));
        assertEquals(3, router.quotesRequired(new BigDecimal("24000")));
    }
}
