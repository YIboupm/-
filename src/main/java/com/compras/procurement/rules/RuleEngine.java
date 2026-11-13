package com.compras.procurement.rules;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuleEngine {

    private final List<ValidationRule> rules;

    public RuleEngine(List<ValidationRule> rules) {
        this.rules = rules;
    }

    public List<RuleViolation> evaluate(ValidationContext context) {
        return rules.stream().flatMap(r -> r.check(context).stream()).toList();
    }
}
