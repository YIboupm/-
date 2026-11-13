package com.compras.procurement.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;
import java.util.Optional;

/**
 * Template of a purchasable category: which fields the assistant must collect and how they are
 * turned into catalog lines.
 *
 * @param assetType for ASSET_COMPATIBILITY categories, the asset type the SKU must fit (e.g. PRINTER)
 * @param intakeEnabled whether employees can request this category through the assistant
 */
@Document("categories")
public record CategoryDefinition(
        @Id String code,
        String name,
        String description,
        List<String> examples,
        ResolutionStrategy resolution,
        String assetType,
        boolean intakeEnabled,
        List<FieldSpec> fields
) {
    public Optional<FieldSpec> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }
}
