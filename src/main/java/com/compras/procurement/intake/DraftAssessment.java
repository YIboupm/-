package com.compras.procurement.intake;

import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.resolution.ResolvedLine;
import com.compras.procurement.rules.RuleViolation;

import java.math.BigDecimal;
import java.util.List;

/** Deterministic verdict on a draft: its lines, price, rule findings and what is still missing. */
public record DraftAssessment(
        List<ResolvedLine> lines,
        BigDecimal estimatedTotal,
        List<RuleViolation> violations,
        List<String> missingFields,
        List<RequirementCheck> checks
) {
    /** Must-haves the standard item does not provably meet. The employee may still accept the standard item. */
    public List<String> unmetMustHaves() {
        return checks.stream().filter(RequirementCheck::blocksStandard).map(RequirementCheck::explain).toList();
    }

    public boolean readyToSubmit() {
        return missingFields.isEmpty() && violations.stream().noneMatch(RuleViolation::blocking) && !lines.isEmpty();
    }

    public List<RuleViolation> warnings() {
        return violations.stream().filter(v -> !v.blocking()).toList();
    }
}
