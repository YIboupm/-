package com.compras.procurement.intake;

import com.compras.procurement.fulfillment.CompanyResource;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.resolution.ResolvedLine;
import com.compras.procurement.rules.RuleViolation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * What the agent proposes after a validated propose_* call. Nothing happens until the employee
 * confirms it: the confirmation is the human approval step.
 */
public record Proposal(
        Type type,
        String title,
        String summary,
        String rationale,
        String categoryCode,
        String categoryName,
        Map<String, Object> fields,
        List<Requirement> requirements,
        List<RequirementCheck> checks,
        List<ResolvedLine> lines,
        BigDecimal estimatedTotal,
        String currency,
        List<RuleViolation> warnings,
        List<String> specialReasons,
        CompanyResource resource,
        BigDecimal avoidedSpend,
        /** Alternatives that also fit but are not used, with the reason. */
        List<com.compras.procurement.request.AlternativeConsidered> considered,
        /** Researched products for a special request, recommended first. */
        List<com.compras.procurement.request.SuggestedModel> candidates,
        /** WITHIN_BUDGET / OVER_BUDGET / NOT_DISCLOSED: the only budget information the employee gets. */
        String budget
) {
    public enum Type { STANDARD, SPECIAL, INTERNAL_USE }
}
