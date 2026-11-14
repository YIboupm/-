package com.compras.procurement.intake.tools;

import com.compras.procurement.fulfillment.CompanyResource;
import com.compras.procurement.fulfillment.CompanyResourceService;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** "Reuse before buy": idle or shared company resources, with live load for the Mac when it is available. */
@Component
public class SearchCompanyResourcesTool implements AgentTool {

    private final CompanyResourceService resources;

    public SearchCompanyResourcesTool(CompanyResourceService resources) {
        this.resources = resources;
    }

    @Override
    public String name() {
        return "search_company_resources";
    }

    @Override
    public String description() {
        return "List resources the company already has and could reuse instead of buying: idle laptops in the "
                + "IT storeroom, shared servers (with live CPU/GPU load when measured), shared printers. Each has "
                + "comparable attributes, a status, a custodian who approves reuse and an access mode.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "type", Schemas.enumOf("COMPUTING (default: laptops, servers and shared compute together, since a "
                        + "shared server can replace a device) or PRINTER.", "COMPUTING", "PRINTER")));
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        List<CompanyResource> found = resources.available(ArgParser.text(args, "type"));
        java.util.Set<String> keys = new java.util.TreeSet<>();
        found.forEach(r -> keys.addAll(r.attributes().keySet()));
        return ToolResult.of(Map.of("resources", found, "attributeKeys", keys,
                        "note", "Register data may be outdated; the custodian confirms availability. Live load is "
                                + "a point-in-time measurement, not a reservation."),
                "search_company_resources · " + found.size() + " resource(s)");
    }
}
