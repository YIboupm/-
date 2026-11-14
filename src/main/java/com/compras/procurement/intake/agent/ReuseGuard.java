package com.compras.procurement.intake.agent;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.fulfillment.CompanyResource;
import com.compras.procurement.fulfillment.CompanyResourceService;
import com.compras.procurement.matching.Requirement;
import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.matching.RequirementMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Use it or explain why not." Before the agent proposes buying something, the system itself checks every
 * reusable company resource (and, for special requests, every catalog item) against the employee's
 * must-haves. Each one that meets all checkable must-haves has to be listed by the agent with a reason it is
 * not used (e.g. "the employee needs a portable device"). The agent keeps its judgement; it cannot skip the
 * comparison.
 */
@Component
public class ReuseGuard {

    private final CompanyResourceService resources;
    private final CatalogService catalog;
    private final RequirementMatcher matcher;

    public ReuseGuard(CompanyResourceService resources, CatalogService catalog, RequirementMatcher matcher) {
        this.resources = resources;
        this.catalog = catalog;
        this.matcher = matcher;
    }

    /** Parses the agent's "considered" list: id -> reason not used. */
    public static Map<String, String> considered(JsonNode node, List<String> problems) {
        Map<String, String> map = new HashMap<>();
        for (JsonNode n : node == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : node) {
            String id = ArgParser.text(n, "id");
            String why = ArgParser.text(n, "why_not");
            if (id == null || why == null) {
                problems.add("Each entry of 'considered' needs an 'id' and a 'why_not'.");
            } else {
                map.put(id.toUpperCase(), why);
            }
        }
        return map;
    }

    /** Names the alternatives the agent listed, for the employee's card and the buyer. */
    public List<com.compras.procurement.request.AlternativeConsidered> describe(Map<String, String> considered) {
        List<com.compras.procurement.request.AlternativeConsidered> list = new ArrayList<>();
        considered.forEach((id, why) -> {
            var resource = resources.find(id);
            if (resource.isPresent()) {
                list.add(new com.compras.procurement.request.AlternativeConsidered(id, resource.get().name(),
                        "Company resource", why));
            } else {
                String name = catalog.findItem(id).map(CatalogItem::name).orElse(id);
                list.add(new com.compras.procurement.request.AlternativeConsidered(id, name, "Catalog item", why));
            }
        });
        return list;
    }

    /**
     * Adds a problem for every fitting alternative the agent did not explain.
     *
     * @param catalogCategory when not null, catalog items of this category are checked too ("" = all items)
     * @param chosenSku       the catalog item being proposed, which needs no explanation
     */
    public void check(List<Requirement> requirements, Map<String, String> considered, String catalogCategory,
                      String chosenSku, List<String> problems) {
        if (requirements.stream().noneMatch(r -> r.isMust() && r.attribute() != null)) {
            return; // nothing checkable: no basis to say an alternative fits
        }
        List<String> unexplained = new ArrayList<>();
        for (CompanyResource r : resources.available(null)) {
            if (!r.busy() && fits(matcher.checkAttributes(r.attributes(), requirements))
                    && !considered.containsKey(r.id().toUpperCase())) {
                unexplained.add("company resource " + r.id() + " (" + r.name() + ", " + r.access() + ")");
            }
        }
        if (catalogCategory != null) {
            for (CatalogItem i : catalog.allItems()) {
                boolean inScope = catalogCategory.isEmpty() || i.categoryCode().equalsIgnoreCase(catalogCategory);
                if (inScope && !i.sku().equalsIgnoreCase(chosenSku == null ? "" : chosenSku)
                        && fits(matcher.checkAttributes(i.attributes(), requirements))
                        && !considered.containsKey(i.sku().toUpperCase())) {
                    unexplained.add("catalog item " + i.sku() + " (" + i.name() + ")");
                }
            }
        }
        if (!unexplained.isEmpty()) {
            problems.add("These alternatives meet all checkable must-haves: " + String.join("; ", unexplained)
                    + ". Either propose one of them (propose_internal_use / propose_standard_request), or ask the "
                    + "employee and list each in 'considered' with the reason it is not used.");
        }
    }

    /** Meets every must-have that the data can check, and at least one is checkable. */
    private static boolean fits(List<RequirementCheck> checks) {
        boolean anyMet = false;
        for (RequirementCheck k : checks) {
            if (!k.requirement().isMust() || k.requirement().attribute() == null) {
                continue;
            }
            if (k.status() != RequirementCheck.Status.MET) {
                return false;
            }
            anyMet = true;
        }
        return anyMet;
    }
}
