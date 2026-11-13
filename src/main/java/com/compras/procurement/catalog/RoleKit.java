package com.compras.procurement.catalog;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

/** Standard equipment bundle for a role, defined once by IT/procurement. */
@Document("role_kits")
public record RoleKit(
        @Id String code,
        String name,
        String description,
        List<String> matchesRoles,
        List<KitItem> items
) {
    public record KitItem(String sku, int quantityPerHire) {
    }
}
