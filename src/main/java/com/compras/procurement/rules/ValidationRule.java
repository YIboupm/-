package com.compras.procurement.rules;

import java.util.List;

/**
 * A business rule applied to a complete or partial draft. Every rule is a Spring bean, so a new
 * policy (e.g. "laptops are replaced at most every 3 years") is one new class.
 */
public interface ValidationRule {

    List<RuleViolation> check(ValidationContext context);
}
