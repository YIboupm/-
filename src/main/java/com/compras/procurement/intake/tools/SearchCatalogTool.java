package com.compras.procurement.intake.tools;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.catalog.RoleKit;
import com.compras.procurement.intake.agent.AgentTool;
import com.compras.procurement.intake.agent.ArgParser;
import com.compras.procurement.intake.agent.Schemas;
import com.compras.procurement.intake.agent.ToolContext;
import com.compras.procurement.intake.agent.ToolException;
import com.compras.procurement.intake.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The standard catalog: categories (with the form fields a request needs), items and role kits. */
@Component
public class SearchCatalogTool implements AgentTool {

    private final CatalogService catalog;

    public SearchCatalogTool(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public String name() {
        return "search_catalog";
    }

    @Override
    public String description() {
        return "Search the company's standard catalog. Returns the categories with the form fields a standard "
                + "request needs, the matching items with specs, comparable attributes, prices, volume prices and "
                + "lead times, and the standard role kits for new hires. Prices and specs exist only here.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Schemas.object(Schemas.props(
                "query", Schemas.string("Optional words describing the need, e.g. \"laptop for ML training\"."),
                "category", Schemas.string("Optional category code to restrict the search, e.g. LAPTOP.")));
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String category = ArgParser.text(args, "category");
        String query = ArgParser.text(args, "query");
        List<CategoryDefinition> categories = catalog.intakeCategories();
        if (category != null && categories.stream().noneMatch(c -> c.code().equalsIgnoreCase(category))) {
            throw new ToolException("Unknown category '" + category + "'. Valid codes: "
                    + categories.stream().map(CategoryDefinition::code).toList());
        }
        List<CatalogItem> items = catalog.allItems().stream()
                .filter(i -> category == null || i.categoryCode().equalsIgnoreCase(category)).toList();
        if (query != null) {
            List<String> words = List.of(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"));
            List<CatalogItem> hits = items.stream().filter(i -> {
                String text = (i.name() + " " + i.description() + " " + i.categoryCode() + " "
                        + String.join(" ", i.suitableFor())).toLowerCase(Locale.ROOT);
                return words.stream().anyMatch(w -> w.length() > 2 && text.contains(w));
            }).toList();
            if (!hits.isEmpty()) {
                items = hits;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", categories.stream()
                .filter(c -> category == null || c.code().equalsIgnoreCase(category))
                .map(this::category).toList());
        out.put("items", items.stream().map(this::item).toList());
        boolean kits = category == null || "ONBOARDING_KIT".equalsIgnoreCase(category);
        if (kits) {
            out.put("roleKits", catalog.kits().stream().map(this::kit).toList());
        }
        out.put("note", "Only these items are standard. Use check_requirements to compare an item with the "
                + "employee's conditions.");
        return ToolResult.of(out, "search_catalog · " + items.size() + " item(s)"
                + (category == null ? "" : " in " + category));
    }

    private Map<String, Object> category(CategoryDefinition c) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldSpec f : c.fields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.name());
            m.put("type", f.type().name());
            m.put("required", f.required());
            m.put("description", f.description());
            if (f.allowedValues() != null) {
                m.put("allowedValues", f.allowedValues());
            }
            if (f.min() != null || f.max() != null) {
                m.put("range", f.min() + ".." + f.max());
            }
            if (f.defaultFrom() != null) {
                m.put("defaultsTo", f.defaultFrom());
            }
            fields.add(m);
        }
        return Map.of("code", c.code(), "name", c.name(), "description", c.description(), "formFields", fields);
    }

    private Map<String, Object> item(CatalogItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", i.sku());
        m.put("category", i.categoryCode());
        m.put("name", i.name());
        m.put("specs", i.specs());
        m.put("attributes", i.attributes());
        m.put("suitableFor", i.suitableFor());
        m.put("listPrice", i.listPrice());
        m.put("priceBreaks", i.priceBreaks());
        m.put("currency", i.currency());
        m.put("leadTimeDays", i.leadTimeDays());
        if (i.compatibleWith() != null && !i.compatibleWith().isEmpty()) {
            m.put("compatibleWith", i.compatibleWith());
        }
        return m;
    }

    private Map<String, Object> kit(RoleKit k) {
        return Map.of("code", k.code(), "name", k.name(), "matchesRoles", k.matchesRoles(), "items", k.items());
    }
}
