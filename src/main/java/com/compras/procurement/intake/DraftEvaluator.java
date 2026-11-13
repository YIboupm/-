package com.compras.procurement.intake;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.matching.RequirementMatcher;
import com.compras.procurement.resolution.LineResolverRegistry;
import com.compras.procurement.resolution.ResolvedLine;
import com.compras.procurement.rules.RuleEngine;
import com.compras.procurement.rules.RuleViolation;
import com.compras.procurement.rules.ValidationContext;
import com.compras.procurement.shared.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Resolves lines, prices them and runs the rules. "What is missing" is computed here from the
 * template, never taken from the model's opinion.
 */
@Component
public class DraftEvaluator {

    private final LineResolverRegistry resolvers;
    private final RuleEngine rules;
    private final RequirementMatcher matcher;
    private final CatalogService catalog;
    private final Clock clock;

    public DraftEvaluator(LineResolverRegistry resolvers, RuleEngine rules, RequirementMatcher matcher,
                          CatalogService catalog, Clock clock) {
        this.resolvers = resolvers;
        this.rules = rules;
        this.matcher = matcher;
        this.catalog = catalog;
        this.clock = clock;
    }

    public DraftAssessment assess(CategoryDefinition category, Map<String, Object> values, EmployeeContext context) {
        return assess(category, values, context, List.of());
    }

    public DraftAssessment assess(CategoryDefinition category, Map<String, Object> values, EmployeeContext context,
                                  List<Requirement> requirements) {
        List<String> missing = category.fields().stream()
                .filter(FieldSpec::required)
                .map(FieldSpec::name)
                .filter(name -> values.get(name) == null)
                .toList();
        List<ResolvedLine> lines = resolvers.resolve(category, values, context);
        BigDecimal total = Money.scale(lines.stream().map(ResolvedLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<RuleViolation> violations = rules.evaluate(
                new ValidationContext(category, values, lines, total, context, LocalDate.now(clock)));
        List<CatalogItem> items = lines.stream().map(l -> catalog.findItem(l.sku()))
                .flatMap(java.util.Optional::stream).toList();
        List<RequirementCheck> checks = matcher.check(items, requirements);
        return new DraftAssessment(lines, total, violations, missing, checks);
    }
}
