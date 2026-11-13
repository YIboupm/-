package com.compras.procurement.request;

import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.resolution.ResolvedLine;
import com.compras.procurement.rules.RuleViolation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Channel-neutral input for creating a request. The chat assistant builds one, and so does
 * the demo loader; a future e-mail or IM channel would too.
 */
public record NewRequest(
        String channel,
        String conversationId,
        EmployeeContext requester,
        CategoryDefinition category,
        Map<String, Object> attributes,
        Map<String, String> attributeSources,
        List<ResolvedLine> lines,
        BigDecimal estimatedTotal,
        String currency,
        List<RuleViolation> warnings,
        /** Non-empty for a special request: these needs are not covered by the catalog and go to a buyer. */
        List<String> specialRequirements,
        /** Every condition the employee stated and how the catalog compares. */
        List<RequirementCheck> requirementChecks,
        /** The employee's own words, verbatim. */
        String originalRequest,
        /** The assistant's explanation of why it chose the catalog option. */
        String mappingRationale
) {
}
