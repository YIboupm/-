package com.compras.procurement.intake.tools;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.fulfillment.CompanyResource;
import com.compras.procurement.fulfillment.CompanyResourceService;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.matching.RequirementMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic judge: compares the employee's conditions with a catalog item or a company
 * resource. Same input, same answer, every time, so "does it fit?" is never the model's opinion.
 */
@Component
public class CheckRequirementsTool implements AgentTool {

    private final CatalogService catalog;
    private final CompanyResourceService resources;
    private final RequirementMatcher matcher;

    public CheckRequirementsTool(CatalogService catalog, CompanyResourceService resources, RequirementMatcher matcher) {
        this.catalog = catalog;
        this.resources = resources;
        this.matcher = matcher;
    }

    @Override
    public String name() {
        return "check_requirements";
    }

    @Override
    public String description() {
        return "Compare the employee's conditions with one catalog item or one company resource. Returns MET, "
                + "NOT_MET (with the actual value) or UNKNOWN (the data does not describe it) for each condition, "
                + "and the attributes the target has. Call it before telling the employee whether something fits.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "target_type", Schemas.enumOf("What to check.", "catalog_item", "company_resource"),
                "target_id", Schemas.string("SKU of the catalog item, or id of the company resource."),
                "requirements", Schemas.requirements()), "target_type", "target_id", "requirements");
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<String> problems = new ArrayList<>();
        String type = ArgParser.required(args, "target_type", problems);
        String id = ArgParser.required(args, "target_id", problems);
        List<Requirement> requirements = ArgParser.requirements(args.path("requirements"), problems);
        if (requirements.isEmpty()) {
            problems.add("Give at least one requirement.");
        }
        if (!problems.isEmpty()) {
            throw new ToolException("Cannot check requirements.", problems);
        }
        Map<String, Object> attributes;
        String name;
        if ("catalog_item".equals(type)) {
            CatalogItem item = catalog.findItem(id).orElseThrow(() -> new ToolException(
                    "Unknown SKU '" + id + "'. Use search_catalog to find valid SKUs."));
            attributes = item.attributes() == null ? Map.of() : item.attributes();
            name = item.name();
        } else if ("company_resource".equals(type)) {
            CompanyResource r = resources.find(id).orElseThrow(() -> new ToolException(
                    "Unknown or unavailable resource '" + id + "'. Use search_company_resources to find ids."));
            attributes = r.attributes();
            name = r.name();
        } else {
            throw new ToolException("target_type must be 'catalog_item' or 'company_resource'.");
        }
        List<RequirementCheck> checks = matcher.checkAttributes(attributes, requirements);
        long mustFailed = checks.stream().filter(k -> k.requirement().isMust()
                && k.status() == RequirementCheck.Status.NOT_MET).count();
        long unknown = checks.stream().filter(k -> k.status() == RequirementCheck.Status.UNKNOWN).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", Map.of("type", type, "id", id, "name", name));
        out.put("checks", checks.stream().map(k -> Map.of(
                "requirement", k.requirement().description(),
                "strength", k.requirement().strength(),
                "status", k.status().name(),
                "actual", k.actual() == null ? "not described" : k.actual())).toList());
        out.put("mustHavesNotMet", mustFailed);
        out.put("unknown", unknown);
        out.put("availableAttributes", attributes);
        if (unknown > 0) {
            out.put("hint", "UNKNOWN means the attribute name did not match or the data does not describe it. "
                    + "Retry with a key from availableAttributes if one has the same meaning.");
        }
        return ToolResult.of(out, "check_requirements · " + name + ": " + (checks.size() - mustFailed - unknown)
                + " met, " + mustFailed + " must-have(s) not met, " + unknown + " unknown");
    }
}
