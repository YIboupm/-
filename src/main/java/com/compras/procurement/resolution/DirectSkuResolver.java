package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.EmployeeContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Laptop tier, monitor, headset ...: the "item" field already holds the SKU. */
@Component
public class DirectSkuResolver implements LineResolver {

    private final CatalogService catalog;

    public DirectSkuResolver(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public ResolutionStrategy strategy() {
        return ResolutionStrategy.DIRECT_SKU;
    }

    @Override
    public List<ResolvedLine> resolve(CategoryDefinition category, Map<String, Object> fields, EmployeeContext context) {
        var sku = FieldValues.string(fields, "item");
        var quantity = FieldValues.integer(fields, "quantity");
        if (sku.isEmpty() || quantity.isEmpty()) {
            return List.of();
        }
        return catalog.findItem(sku.get())
                .filter(item -> item.categoryCode().equals(category.code()))
                .map(item -> List.of(ResolvedLine.of(item, quantity.get(), null)))
                .orElse(List.of());
    }
}
