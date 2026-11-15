package com.compras.procurement.intake.tools;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.fulfillment.CompanyResource;
import com.compras.procurement.fulfillment.CompanyResourceService;
import com.compras.procurement.intake.IntakeProperties;
import com.compras.procurement.intake.Proposal;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.matching.RequirementMatcher;
import com.compras.procurement.shared.Money;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** "Reuse before buy": proposes an existing company resource instead of a purchase. */
@Component
public class ProposeInternalUseTool implements AgentTool {

    private final CompanyResourceService resources;
    private final CatalogService catalog;
    private final RequirementMatcher matcher;
    private final IntakeProperties properties;

    public ProposeInternalUseTool(CompanyResourceService resources, CatalogService catalog,
                                  RequirementMatcher matcher, IntakeProperties properties) {
        this.resources = resources;
        this.catalog = catalog;
        this.matcher = matcher;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "propose_internal_use";
    }

    @Override
    public String description() {
        return "Propose using an existing company resource instead of buying. The system re-checks the employee's "
                + "requirements and the live status; must-haves must be met. If valid, the employee sees the proposal "
                + "and must confirm it; the custodian then grants access. Give the catalog item that would otherwise "
                + "have been bought so the avoided spend is recorded.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "resource_id", Schemas.string("Id from search_company_resources."),
                "requirements", Schemas.requirements(),
                "usage_summary", Schemas.string("What the employee will use it for, and any limits they accepted."),
                "avoided_purchase_sku", Schemas.string("Optional catalog SKU that would otherwise be bought."),
                "avoided_quantity", Schemas.integer("Optional quantity for the avoided purchase.")),
                "resource_id", "requirements", "usage_summary");
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<String> problems = new ArrayList<>();
        String id = ArgParser.required(args, "resource_id", problems);
        String usage = ArgParser.required(args, "usage_summary", problems);
        List<Requirement> requirements = ArgParser.requirements(args.path("requirements"), problems);
        CompanyResource resource = id == null ? null : resources.find(id).orElse(null);
        if (id != null && resource == null) {
            problems.add("Unknown or unavailable resource '" + id + "'. Use search_company_resources.");
        }
        BigDecimal avoided = null;
        String sku = ArgParser.text(args, "avoided_purchase_sku");
        if (sku != null) {
            CatalogItem item = catalog.findItem(sku).orElse(null);
            if (item == null) {
                problems.add("Unknown avoided_purchase_sku '" + sku + "'.");
            } else {
                int qty = Math.max(1, args.path("avoided_quantity").asInt(1));
                avoided = Money.times(item.unitPriceFor(qty), qty);
            }
        }
        if (!problems.isEmpty()) {
            throw new ToolException("The internal-use proposal was not accepted.", problems);
        }
        if (resource.busy()) {
            throw new ToolException("The resource is busy right now (live measurement). Tell the employee and offer "
                    + "the alternatives.");
        }
        List<RequirementCheck> checks = matcher.checkAttributes(resource.attributes(), requirements);
        checks.stream().filter(k -> k.requirement().isMust() && k.status() == RequirementCheck.Status.NOT_MET)
                .forEach(k -> problems.add("Must-have not met by " + resource.name() + ": " + k.explain()));
        if (!problems.isEmpty()) {
            throw new ToolException("This resource does not meet the must-haves. Do not propose it, or ask the "
                    + "employee whether these are really required.", problems);
        }
        Proposal proposal = new Proposal(Proposal.Type.INTERNAL_USE, "Use " + resource.name(), usage, null, null,
                null, Map.of(), requirements, checks, List.of(), null, properties.currency(), List.of(), List.of(),
                resource, avoided, List.of(), List.of(), null);
        return ToolResult.propose(proposal, "propose_internal_use · " + resource.name());
    }
}
