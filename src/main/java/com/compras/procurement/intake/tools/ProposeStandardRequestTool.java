package com.compras.procurement.intake.tools;

import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.intake.DraftAssessment;
import com.compras.procurement.intake.DraftEvaluator;
import com.compras.procurement.intake.FieldNormalizer;
import com.compras.procurement.intake.IntakeProperties;
import com.compras.procurement.intake.Proposal;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.rules.RuleViolation;
import com.fasterxml.jackson.databind.JsonNode;
import com.compras.procurement.budget.BudgetGate;
import com.compras.procurement.budget.BudgetVerdict;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The standard purchase form. The model fills it; the system validates it against the category template,
 * the catalog and the business rules and returns every problem to the model. A valid form is shown to the
 * employee, who has to confirm it.
 */
@Component
public class ProposeStandardRequestTool implements AgentTool {

    private final CatalogService catalog;
    private final FieldNormalizer normalizer;
    private final DraftEvaluator evaluator;
    private final IntakeProperties properties;
    private final com.compras.procurement.intake.agent.ReuseGuard reuse;
    /** Finance boundary: a yes/no verdict for this request's amount, never a figure. */
    private final BudgetGate budget;
    private final com.compras.procurement.policy.PolicyRouter router;

    public ProposeStandardRequestTool(CatalogService catalog, FieldNormalizer normalizer, DraftEvaluator evaluator,
                                      IntakeProperties properties, com.compras.procurement.intake.agent.ReuseGuard reuse,
                                      BudgetGate budget, com.compras.procurement.policy.PolicyRouter router) {
        this.budget = budget;
        this.router = router;
        this.reuse = reuse;
        this.catalog = catalog;
        this.normalizer = normalizer;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "propose_standard_request";
    }

    @Override
    public String description() {
        return "Propose a standard purchase request from the catalog. 'fields' follows the category's formFields "
                + "from search_catalog (e.g. item, quantity, neededBy, justification). The system validates it and "
                + "returns problems to fix; if valid, the employee sees the form and must confirm it. Use only values "
                + "the employee gave or confirmed. If the item does not meet the employee's must-haves, ask the "
                + "employee first, or use propose_special_request.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "category", Schemas.string("Category code, e.g. LAPTOP."),
                "fields", Schemas.freeObject("Form field name to value, per the category's formFields."),
                "requirements", Schemas.requirements(),
                "summary", Schemas.string("One sentence for the employee: what will be ordered and why."),
                "rationale", Schemas.string("Why this catalog option fits the need."),
                "over_budget_explained", Schemas.anyValue("Set true only after the system said OVER_BUDGET and you "
                        + "told the employee."),
                com.compras.procurement.intake.agent.StrategicGate.ARG,
                com.compras.procurement.intake.agent.StrategicGate.schema(),
                "considered", Schemas.considered()),
                "category", "fields", "summary");
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<String> problems = new ArrayList<>();
        String code = ArgParser.required(args, "category", problems);
        CategoryDefinition category = code == null ? null : catalog.findCategory(code.toUpperCase())
                .filter(CategoryDefinition::intakeEnabled).orElse(null);
        if (code != null && category == null) {
            problems.add("Unknown category '" + code + "'. Valid: "
                    + catalog.intakeCategories().stream().map(CategoryDefinition::code).toList());
        }
        List<Requirement> requirements = ArgParser.requirements(args.path("requirements"), problems);
        if (category == null) {
            throw new ToolException("The form was not accepted.", problems);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        JsonNode fields = args.path("fields");
        if (!fields.isObject()) {
            problems.add("'fields' must be an object of field name to value.");
        } else {
            Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                FieldSpec spec = category.field(e.getKey()).orElse(null);
                if (spec == null) {
                    problems.add("Unknown field '" + e.getKey() + "'. Fields of " + category.code() + ": "
                            + category.fields().stream().map(FieldSpec::name).toList());
                    continue;
                }
                FieldNormalizer.Result r = normalizer.normalize(spec, raw(e.getValue()), category,
                        context.requester(), context.today());
                if (r.isOk()) {
                    values.put(spec.name(), r.value());
                } else if (r.problem() != null) {
                    problems.add(r.problem());
                }
            }
        }
        List<String> defaults = new ArrayList<>();
        for (FieldSpec f : category.fields()) {
            if (values.containsKey(f.name()) || f.defaultFrom() == null) {
                continue;
            }
            Object value = switch (f.defaultFrom()) {
                case "employee.site" -> context.requester().employee().site();
                case "employee.department" -> context.requester().employee().department();
                default -> f.defaultFrom().startsWith("const:") ? normalizer.normalize(f, f.defaultFrom().substring(6),
                        category, context.requester(), context.today()).value() : null;
            };
            if (value != null) {
                values.put(f.name(), value);
                defaults.add(f.name() + "=" + value);
            }
        }
        for (FieldSpec f : category.fields()) {
            if (f.required() && !values.containsKey(f.name())) {
                problems.add("Missing required field '" + f.name() + "' (" + f.type() + "): " + f.description()
                        + " Ask the employee if you do not know it.");
            }
        }
        if (!problems.isEmpty()) {
            throw new ToolException("The form was not accepted.", problems);
        }

        DraftAssessment a = evaluator.assess(category, values, context.requester(), requirements);
        a.violations().stream().filter(RuleViolation::blocking).forEach(v -> problems.add(v.message()));
        if (a.lines().isEmpty()) {
            problems.add("These fields do not resolve to any catalog item.");
        }
        if (!a.unmetMustHaves().isEmpty()) {
            problems.add("The standard item does not meet these must-haves: " + String.join("; ", a.unmetMustHaves())
                    + ". Ask the employee whether they accept the standard item anyway (then propose again with "
                    + "those requirements as strength 'preference'), or use propose_special_request.");
        }
        Map<String, String> considered = com.compras.procurement.intake.agent.ReuseGuard
                .considered(args.path("considered"), problems);
        reuse.check(requirements, considered, null, null, problems);
        if (!problems.isEmpty()) {
            throw new ToolException("The form was not accepted.", problems);
        }

        String title = a.lines().stream().map(l -> l.quantity() + " x " + l.name()).collect(Collectors.joining(", "));

        // Strategic (policy 7): computed by the router on the whole contract value; the employee hears it first.
        boolean framework = category.resolution() == com.compras.procurement.catalog.ResolutionStrategy.CONSUMPTION;
        com.compras.procurement.policy.RouteDecision route = router.route(new com.compras.procurement.policy
                .PolicyRouter.Input(false, framework, a.estimatedTotal(), values));
        ToolResult gate = com.compras.procurement.intake.agent.StrategicGate.check(route, args, context, name());
        if (gate != null) {
            return gate;
        }
        List<RuleViolation> warnings = new ArrayList<>(a.warnings());
        if (route.strategic()) {
            warnings.add(RuleViolation.warning("strategic", "Strategic purchase: " + String.join("; ",
                    route.reasons()) + ". The category manager decides how it is sourced before approvals."));
        }

        // Budget: the system checks the amount it computed itself (the model cannot pick an amount to probe)
        // and gets a verdict only. Over budget may still be submitted; the employee must be told first.
        String department = values.get("chargeTo") instanceof String s ? s
                : context.requester().employee().department();
        // the current quarter's charge: all of a purchase, the months left this quarter for a cloud call-off
        BudgetVerdict verdict = budgetVerdict(context, department,
                com.compras.procurement.request.SpendProfile.quarterCharge(values, a.estimatedTotal(), context.today()));
        boolean explained = args.path("over_budget_explained").asBoolean(false)
                || "true".equalsIgnoreCase(args.path("over_budget_explained").asText());
        if (verdict == BudgetVerdict.OVER_BUDGET && !explained) {
            return ToolResult.of(Map.of("budget", verdict.name(),
                    "next", "This request is over the department's remaining budget for the quarter. Tell the "
                            + "employee in their language: it can still be submitted, and the Finance Controller "
                            + "will decide whether to approve it. You do not know any budget figures; do not guess "
                            + "or mention amounts. Then call propose_standard_request again with the same fields "
                            + "and over_budget_explained=true."),
                    "propose_standard_request · over budget: employee to be told first");
        }
        Proposal proposal = new Proposal(Proposal.Type.STANDARD, title, ArgParser.text(args, "summary"),
                ArgParser.text(args, "rationale"), category.code(), category.name(), values, requirements, a.checks(),
                a.lines(), a.estimatedTotal(), properties.currency(), warnings, List.of(), null, null,
                reuse.describe(considered), List.of(), verdict.name(), List.of());
        return ToolResult.propose(proposal, "propose_standard_request · " + title + " · "
                + a.estimatedTotal() + " " + properties.currency()
                + (defaults.isEmpty() ? "" : " (defaults: " + String.join(", ", defaults) + ")"));
    }

    /** One verdict per distinct amount; at most {@code budgetChecks()} per conversation, then nothing more. */
    private BudgetVerdict budgetVerdict(ToolContext context, String department, java.math.BigDecimal amount) {
        var conversation = context.conversation();
        String key = department + "|" + (amount == null ? "" : amount.stripTrailingZeros().toPlainString());
        String known = conversation.getBudgetChecks().get(key);
        if (known != null) {
            return BudgetVerdict.valueOf(known);
        }
        if (conversation.getBudgetChecks().size() >= properties.budgetChecks()) {
            return BudgetVerdict.NOT_DISCLOSED;
        }
        BudgetVerdict verdict = budget.verdict(department, amount);
        conversation.recordBudgetCheck(key, verdict.name());
        return verdict;
    }

    static Object raw(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.numberValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        return v.isValueNode() ? v.asText() : v.toString();
    }
}
