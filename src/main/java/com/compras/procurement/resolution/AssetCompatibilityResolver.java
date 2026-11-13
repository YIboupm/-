package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.Asset;
import com.compras.procurement.context.EmployeeContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Toner: the model identifies the printer from "the one on the 3rd floor"; the cartridge is
 * then looked up from the asset model and the compatibility table. The employee never needs
 * to know a cartridge number.
 */
@Component
public class AssetCompatibilityResolver implements LineResolver {

    private final CatalogService catalog;

    public AssetCompatibilityResolver(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public ResolutionStrategy strategy() {
        return ResolutionStrategy.ASSET_COMPATIBILITY;
    }

    @Override
    public List<ResolvedLine> resolve(CategoryDefinition category, Map<String, Object> fields, EmployeeContext context) {
        Optional<Asset> asset = assetField(category).flatMap(name -> FieldValues.string(fields, name)).flatMap(context::asset);
        var quantity = FieldValues.integer(fields, "quantity");
        if (asset.isEmpty() || quantity.isEmpty()) {
            return List.of();
        }
        List<CatalogItem> compatible = catalog.compatibleItems(category.code(), asset.get().model());
        if (compatible.isEmpty()) {
            return List.of();
        }
        String note = "Fits " + asset.get().model() + " (" + asset.get().assetTag() + ")";
        return List.of(ResolvedLine.of(compatible.get(0), quantity.get(), note));
    }

    private Optional<String> assetField(CategoryDefinition category) {
        return category.fields().stream()
                .filter(f -> f.type() == com.compras.procurement.catalog.FieldType.ASSET)
                .map(f -> f.name())
                .findFirst();
    }
}
