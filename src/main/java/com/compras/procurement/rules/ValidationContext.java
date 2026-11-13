package com.compras.procurement.rules;

import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.resolution.ResolvedLine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Everything a rule may look at. Rules never call the model. */
public record ValidationContext(
        CategoryDefinition category,
        Map<String, Object> fields,
        List<ResolvedLine> lines,
        BigDecimal estimatedTotal,
        EmployeeContext requester,
        LocalDate today
) {
}
