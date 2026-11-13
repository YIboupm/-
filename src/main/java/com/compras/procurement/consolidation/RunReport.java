package com.compras.procurement.consolidation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Result of one consolidation run: what was released, what was held and why. */
public record RunReport(LocalDate asOf, List<GroupDecision> decisions, int ordersCreated, BigDecimal savings) {
}
