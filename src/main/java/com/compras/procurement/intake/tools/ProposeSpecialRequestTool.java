package com.compras.procurement.intake.tools;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.intake.IntakeProperties;
import com.compras.procurement.intake.Proposal;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.budget.BudgetVerdict;
import com.compras.procurement.intake.agent.ReuseGuard;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.matching.RequirementMatcher;
import com.compras.procurement.request.SuggestedModel;
import com.compras.procurement.research.WebResearch;
import com.compras.procurement.resolution.ResolvedLine;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A structured brief for a buyer when the catalog cannot meet the need. */
@Component
public class ProposeSpecialRequestTool implements AgentTool {

    private final CatalogService catalog;
    private final EmployeeContextService employees;
    private final RequirementMatcher matcher;
    private final IntakeProperties properties;
    private final ReuseGuard reuse;
    private final WebResearch research;

    public ProposeSpecialRequestTool(CatalogService catalog, EmployeeContextService employees,
                                     RequirementMatcher matcher, IntakeProperties properties, ReuseGuard reuse,
                                     WebResearch research, com.compras.procurement.policy.PolicyRouter router) {
        this.router = router;
        this.reuse = reuse;
        this.research = research;
        this.catalog = catalog;
        this.employees = employees;
        this.matcher = matcher;
        this.properties = properties;
    }

    private final com.compras.procurement.policy.PolicyRouter router;

    @Override
    public String name() {
        return "propose_special_request";
    }

    @Override
    public String description() {
        return "Propose a special (non-catalog) request for a buyer to source, when no standard item or company "
                + "resource meets the employee's must-haves. Include why the standard options do not fit, and 1-3 "
                + "researched candidate products (web_search / fetch_page) so a non-technical buyer knows what to "
                + "order; mark the one the employee chose. The system validates it; if valid, the employee sees the "
                + "brief and must confirm it.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "title", Schemas.string("Short title, e.g. \"Mac for Metal ML development\"."),
                "description", Schemas.string("What is needed and what it is for, keeping the employee's reasons."),
                "quantity", Schemas.integer("How many."),
                "needed_by", Schemas.string("yyyy-MM-dd, only if the employee gave or confirmed it."),
                "charge_to", Schemas.string("Department name or cost centre that pays; defaults to the requester's "
                        + "department."),
                "category", Schemas.string("Category code only if it really is that kind of thing (e.g. CLOUD for "
                        + "a cloud provider outside the company agreements); otherwise omit it."),
                "reference_sku", Schemas.string("Optional closest catalog SKU, for the buyer's reference."),
                "requirements", Schemas.requirements(),
                "reasons", Schemas.array(Schemas.string("One reason."),
                        "Why the standard catalog and the company resources do not fit."),
                "considered", Schemas.considered(),
                "term_months", Schemas.integer("For a service or subscription: how many months it runs. Omit for a "
                        + "one-off purchase."),
                "data_classification", Schemas.enumOf("Most sensitive data the supplier will handle, if any.",
                        "Public", "Internal", "Confidential", "Restricted"),
                "sole_source", Schemas.anyValue("true only if the employee confirmed that just one supplier can "
                        + "provide it (exclusive supply, compatibility with what the company has, intellectual "
                        + "property, the maker's own maintenance). A preferred brand is not sole source: record it as "
                        + "a preference in requirements."),
                "sole_source_reason", Schemas.string("Why only that supplier (required with sole_source)."),
                com.compras.procurement.intake.agent.StrategicGate.ARG,
                com.compras.procurement.intake.agent.StrategicGate.schema(),
                "items", Schemas.array(Schemas.object(Schemas.props(
                        "name", Schemas.string("What this line is, e.g. \"Height-adjustable desk\"."),
                        "quantity", Schemas.integer("How many of this line."),
                        "requirements", Schemas.requirements(),
                        "preferred_model", Schemas.string("The product the employee chose for this line, if any."),
                        "reference_unit_price", Schemas.anyValue("Price of ONE unit of this line in EUR, as a page you "
                                + "saw in this conversation states it (number). Omit if no page gave a price."),
                        "reference_url", Schemas.string("The page the reference price is from (required with it).")),
                        "name", "quantity", "requirements"),
                        "The different things needed, one line each, when there is more than one (a desk and a chair "
                                + "are two lines; a laptop and its charger in one box are one). Each line is quoted, "
                                + "ordered and received on its own. Omit for a single thing."),
                "candidates", Schemas.array(Schemas.object(Schemas.props(
                        "model", Schemas.string("Product, e.g. \"Mac Studio (M4 Max)\"."),
                        "configuration", Schemas.string("Configuration that meets the must-haves."),
                        "fits", Schemas.string("How it meets the must-haves, with the numbers from the source."),
                        "trade_offs", Schemas.string("What speaks against it (portability, price, lead time...)."),
                        "price", Schemas.string("List price as the source states it, with currency and whether "
                                + "VAT is included. Omit if no source gave a price."),
                        "price_source_url", Schemas.string("URL the price is from (required with price)."),
                        "sources", Schemas.array(Schemas.string("URL."), "URLs the specs are from: pages you "
                                + "found with web_search or read with fetch_page in this conversation."),
                        "preferred", Schemas.anyValue("true for the option the employee chose (at most one).")),
                        "model", "configuration", "fits", "sources"),
                        "1-3 researched products, recommended first.")),
                "title", "description", "quantity", "requirements", "reasons");
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<String> problems = new ArrayList<>();
        String title = ArgParser.required(args, "title", problems);
        String description = ArgParser.required(args, "description", problems);
        int quantity = args.path("quantity").asInt(0);
        if (quantity < 1 || quantity > 1000) {
            problems.add("'quantity' must be a whole number between 1 and 1000.");
        }
        String neededBy = ArgParser.text(args, "needed_by");
        if (neededBy != null) {
            try {
                if (LocalDate.parse(neededBy).isBefore(context.today())) {
                    problems.add("'needed_by' " + neededBy + " is in the past.");
                }
            } catch (DateTimeParseException e) {
                problems.add("'needed_by' must be yyyy-MM-dd.");
            }
        }
        String chargeTo = ArgParser.text(args, "charge_to");
        String department = chargeTo == null ? context.requester().employee().department()
                : employees.department(chargeTo).map(d -> d.name()).orElse(null);
        if (department == null) {
            problems.add("Unknown department '" + chargeTo + "'. Valid: "
                    + employees.departments().stream().map(d -> d.name()).toList());
        }
        CategoryDefinition category = null;
        String code = ArgParser.text(args, "category");
        if (code != null) {
            category = catalog.findCategory(code.toUpperCase()).orElse(null);
            if (category == null) {
                problems.add("Unknown category '" + code + "'.");
            }
        }
        CatalogItem reference = null;
        String sku = ArgParser.text(args, "reference_sku");
        if (sku != null) {
            reference = catalog.findItem(sku).orElse(null);
            if (reference == null) {
                problems.add("Unknown reference_sku '" + sku + "'.");
            }
        }
        List<Requirement> requirements = ArgParser.requirements(args.path("requirements"), problems);
        List<String> reasons = new ArrayList<>();
        args.path("reasons").forEach(r -> {
            if (!r.asText().isBlank()) {
                reasons.add(r.asText().trim());
            }
        });
        if (reasons.isEmpty()) {
            problems.add("'reasons' must say why the standard catalog and the company resources do not fit.");
        }
        // Policy attributes the routing needs (policy 7)
        Map<String, Object> policyAttributes = new LinkedHashMap<>();
        int term = args.path("term_months").asInt(0);
        if (term > 0) {
            policyAttributes.put("termMonths", term);
        }
        String data = ArgParser.text(args, "data_classification");
        if (data != null) {
            policyAttributes.put("dataClassification", data);
        }
        boolean soleSource = args.path("sole_source").asBoolean(false)
                || "true".equalsIgnoreCase(args.path("sole_source").asText());
        if (soleSource) {
            String why = ArgParser.text(args, "sole_source_reason");
            if (why == null) {
                problems.add("'sole_source_reason' is required with sole_source.");
            }
            policyAttributes.put("soleSource", true);
            policyAttributes.put("soleSourceReason", why);
        }
        // Strategic purchases (policy 7, 11): the category manager decides how to source them. The assistant records
        // the need; it does not research or recommend suppliers, so candidates are refused here, by the program.
        boolean framework = category != null
                && category.resolution() == com.compras.procurement.catalog.ResolutionStrategy.CONSUMPTION;
        com.compras.procurement.policy.RouteDecision route = router.route(new com.compras.procurement.policy
                .PolicyRouter.Input(true, framework, null, policyAttributes));
        if (route.strategic() && args.path("candidates").isArray() && !args.path("candidates").isEmpty()) {
            problems.add("This is a strategic purchase (" + String.join("; ", route.reasons()) + "): the category "
                    + "manager decides how to source it. Remove 'candidates': record the need and the reasons only, "
                    + "do not research or recommend suppliers.");
        }
        if (problems.isEmpty()) {
            ToolResult gate = com.compras.procurement.intake.agent.StrategicGate.check(route, args, context, name());
            if (gate != null) {
                return gate;
            }
        }
        List<com.compras.procurement.request.SpecialItem> items = items(args.path("items"), route.strategic(), context,
                problems);
        List<SuggestedModel> candidates = route.strategic() ? List.of()
                : candidates(args.path("candidates"), context, problems);
        Map<String, String> considered = ReuseGuard.considered(args.path("considered"), problems);
        if (problems.isEmpty()) {
            List<Requirement> all = new ArrayList<>(requirements);
            items.forEach(i -> all.addAll(i.requirements()));
            reuse.check(all, considered, "", null, problems);
        }
        if (!problems.isEmpty()) {
            throw new ToolException("The special request was not accepted.", problems);
        }

        // Without a reference item there is nothing to compare: the buyer sources against the requirements.
        List<RequirementCheck> checks = reference == null ? List.of()
                : matcher.checkAttributes(reference.attributes(), requirements);
        List<ResolvedLine> lines = reference == null ? List.of()
                : List.of(ResolvedLine.of(reference, quantity, "Closest standard item, for reference only"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("quantity", quantity);
        if (neededBy != null) {
            fields.put("neededBy", neededBy);
        }
        fields.put("chargeTo", department);
        fields.put("deliverySite", context.requester().employee().site());
        fields.put("justification", description);
        fields.putAll(policyAttributes);
        BigDecimal total = lines.isEmpty() ? null : lines.get(0).lineTotal();
        List<com.compras.procurement.rules.RuleViolation> notes = route.strategic()
                ? List.of(com.compras.procurement.rules.RuleViolation.warning("strategic", "Strategic purchase: "
                        + String.join("; ", route.reasons()) + ". The category manager decides how it is sourced; "
                        + "the assistant does not recommend suppliers for it."))
                : List.of();
        Proposal proposal = new Proposal(Proposal.Type.SPECIAL, title, description, null,
                category == null ? null : category.code(), category == null ? "Non-catalog request" : category.name(),
                fields, requirements, checks, lines, total, properties.currency(), notes, reasons, null, null,
                reuse.describe(considered), candidates, BudgetVerdict.NOT_DISCLOSED.name(), items);
        return ToolResult.propose(proposal, "propose_special_request · " + title);
    }

    /** The lines of the request. A reference price must come from a page seen in this conversation. */
    private List<com.compras.procurement.request.SpecialItem> items(JsonNode node, boolean strategic,
                                                                     ToolContext context, List<String> problems) {
        List<com.compras.procurement.request.SpecialItem> list = new ArrayList<>();
        if (!node.isArray()) {
            return list;
        }
        if (node.size() > 10) {
            problems.add("At most 10 lines.");
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode n = node.get(i);
            String at = "items[" + i + "]";
            String name = ArgParser.text(n, "name");
            int quantity = n.path("quantity").asInt(0);
            if (name == null) {
                problems.add(at + " needs a 'name'.");
            }
            if (quantity < 1 || quantity > 1000) {
                problems.add(at + ": 'quantity' must be a whole number between 1 and 1000.");
            }
            List<Requirement> reqs = ArgParser.requirements(n.path("requirements"), problems);
            java.math.BigDecimal price = null;
            String url = ArgParser.text(n, "reference_url");
            JsonNode p = n.path("reference_unit_price");
            if (!p.isMissingNode() && !p.isNull() && !p.asText().isBlank()) {
                try {
                    price = new java.math.BigDecimal(p.asText().replace("€", "").replace(",", ".").trim());
                } catch (NumberFormatException e) {
                    problems.add(at + ": 'reference_unit_price' must be a number in EUR, e.g. 289.99.");
                }
                if (strategic) {
                    problems.add(at + ": a strategic purchase has no researched prices (the category manager sources it). "
                            + "Remove 'reference_unit_price'.");
                } else if (url == null || !context.conversation().hasSource(url)) {
                    problems.add(at + ": a reference price needs 'reference_url', a page seen in this conversation.");
                }
            }
            list.add(new com.compras.procurement.request.SpecialItem(String.valueOf(i + 1), name, quantity, reqs,
                    price, price == null ? null : url, strategic ? null : ArgParser.text(n, "preferred_model")));
        }
        return list;
    }

    /** Every spec and price must come from a page the agent actually saw in this conversation. */
    private List<SuggestedModel> candidates(JsonNode node, ToolContext context, List<String> problems) {
        List<SuggestedModel> list = new ArrayList<>();
        if (!node.isArray() || node.isEmpty()) {
            if (research.available()) {
                problems.add("'candidates' is required: research 1-3 concrete products with web_search / "
                        + "fetch_page, discuss them with the employee, and cite the pages.");
            }
            return list;
        }
        if (node.size() > 3) {
            problems.add("Give at most 3 candidates.");
        }
        int preferred = 0;
        for (int i = 0; i < node.size(); i++) {
            JsonNode c = node.get(i);
            String at = "candidates[" + i + "]";
            String model = ArgParser.text(c, "model");
            String configuration = ArgParser.text(c, "configuration");
            String fits = ArgParser.text(c, "fits");
            if (model == null || configuration == null || fits == null) {
                problems.add(at + " needs 'model', 'configuration' and 'fits'.");
            }
            List<String> sources = new ArrayList<>();
            c.path("sources").forEach(u -> {
                if (!u.asText().isBlank()) {
                    sources.add(u.asText().trim());
                }
            });
            if (sources.isEmpty()) {
                problems.add(at + " needs at least one source URL.");
            }
            for (String u : sources) {
                if (!context.conversation().hasSource(u)) {
                    problems.add(at + ": " + u + " was not found with web_search or read with fetch_page in this "
                            + "conversation. Cite only pages you actually saw.");
                }
            }
            String price = ArgParser.text(c, "price");
            String priceUrl = ArgParser.text(c, "price_source_url");
            if (price != null && (priceUrl == null || !context.conversation().hasSource(priceUrl))) {
                problems.add(at + ": a price needs 'price_source_url', a page seen in this conversation. Omit the "
                        + "price if no source states it.");
            }
            boolean isPreferred = c.path("preferred").asBoolean(false)
                    || "true".equalsIgnoreCase(c.path("preferred").asText());
            if (isPreferred) {
                preferred++;
            }
            list.add(new SuggestedModel(model, configuration, fits, ArgParser.text(c, "trade_offs"), price,
                    priceUrl, sources, isPreferred));
        }
        if (preferred > 1) {
            problems.add("Mark at most one candidate as 'preferred'.");
        }
        return list;
    }
}
