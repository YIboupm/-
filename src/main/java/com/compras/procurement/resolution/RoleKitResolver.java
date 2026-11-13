package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.catalog.RoleKit;
import com.compras.procurement.context.EmployeeContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Onboarding: "three developers start next month" becomes one line per kit item,
 * each multiplied by the headcount. One sentence, several standard lines.
 */
@Component
public class RoleKitResolver implements LineResolver {

    private final CatalogService catalog;

    public RoleKitResolver(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public ResolutionStrategy strategy() {
        return ResolutionStrategy.ROLE_KIT;
    }

    @Override
    public List<ResolvedLine> resolve(CategoryDefinition category, Map<String, Object> fields, EmployeeContext context) {
        var kitCode = FieldValues.string(fields, "role");
        var headcount = FieldValues.integer(fields, "headcount");
        if (kitCode.isEmpty() || headcount.isEmpty()) {
            return List.of();
        }
        var kit = catalog.findKit(kitCode.get());
        if (kit.isEmpty()) {
            return List.of();
        }
        List<ResolvedLine> lines = new ArrayList<>();
        for (RoleKit.KitItem kitItem : kit.get().items()) {
            catalog.findItem(kitItem.sku()).ifPresent(item -> lines.add(ResolvedLine.of(
                    item, kitItem.quantityPerHire() * headcount.get(), kit.get().name())));
        }
        return lines;
    }
}
