package com.compras.procurement.rules;

import com.compras.procurement.intake.IntakeProperties;
import com.compras.procurement.resolution.FieldValues;
import com.compras.procurement.resolution.ResolvedLine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The rules active in the demo. Each is an independent bean picked up by {@link RuleEngine}. */
public final class Rules {

    private Rules() {
    }

    /** All fields are filled but nothing in the catalog matches (e.g. a printer with no known toner). */
    @Component
    public static class ResolvableRule implements ValidationRule {
        @Override
        public List<RuleViolation> check(ValidationContext ctx) {
            boolean allRequiredPresent = ctx.category().fields().stream()
                    .filter(f -> f.required())
                    .allMatch(f -> ctx.fields().get(f.name()) != null);
            if (allRequiredPresent && ctx.lines().isEmpty()) {
                return List.of(RuleViolation.error(null,
                        "No catalog item matches this request. It will need a buyer's attention."));
            }
            return List.of();
        }
    }

    /** Warns when the supplier lead time makes the needed-by date unrealistic. */
    @Component
    public static class LeadTimeRule implements ValidationRule {
        @Override
        public List<RuleViolation> check(ValidationContext ctx) {
            var neededBy = FieldValues.date(ctx.fields(), "neededBy");
            if (neededBy.isEmpty() || ctx.lines().isEmpty()) {
                return List.of();
            }
            int maxLead = ctx.lines().stream().mapToInt(ResolvedLine::leadTimeDays).max().orElse(0);
            LocalDate earliest = ctx.today().plusDays(maxLead);
            if (neededBy.get().isBefore(earliest)) {
                return List.of(RuleViolation.warning("neededBy",
                        "Supplier lead time is " + maxLead + " days; earliest realistic delivery is " + earliest
                                + ". Mark the request as urgent if this blocks work."));
            }
            return List.of();
        }
    }

    /** Large requests stay allowed but are flagged for a higher approval level. */
    @Component
    public static class BudgetThresholdRule implements ValidationRule {
        private final IntakeProperties properties;

        public BudgetThresholdRule(IntakeProperties properties) {
            this.properties = properties;
        }

        @Override
        public List<RuleViolation> check(ValidationContext ctx) {
            BigDecimal threshold = properties.budgetApprovalThreshold();
            if (threshold != null && ctx.estimatedTotal().compareTo(threshold) > 0) {
                return List.of(RuleViolation.warning(null,
                        "Estimated total exceeds " + threshold + " " + properties.currency()
                                + "; director approval will be required."));
            }
            return List.of();
        }
    }
}
