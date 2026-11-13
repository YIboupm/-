package com.compras.procurement.catalog;

import com.compras.procurement.shared.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Read access to the category registry: templates, catalog items and role kits. */
@Service
public class CatalogService {

    private final CategoryRepository categories;
    private final CatalogItemRepository items;
    private final RoleKitRepository kits;

    public CatalogService(CategoryRepository categories, CatalogItemRepository items, RoleKitRepository kits) {
        this.categories = categories;
        this.items = items;
        this.kits = kits;
    }

    public List<CategoryDefinition> intakeCategories() {
        return categories.findByIntakeEnabledTrue();
    }

    public Optional<CategoryDefinition> findCategory(String code) {
        return code == null ? Optional.empty() : categories.findById(code);
    }

    public CategoryDefinition category(String code) {
        return findCategory(code).orElseThrow(() -> new NotFoundException("Unknown category " + code));
    }

    public List<CatalogItem> itemsOf(String categoryCode) {
        return items.findByCategoryCode(categoryCode);
    }

    public List<CatalogItem> allItems() {
        return items.findAll();
    }

    public Optional<CatalogItem> findItem(String sku) {
        return sku == null ? Optional.empty() : items.findById(sku);
    }

    public CatalogItem item(String sku) {
        return findItem(sku).orElseThrow(() -> new NotFoundException("Unknown SKU " + sku));
    }

    public List<CatalogItem> compatibleItems(String categoryCode, String assetModel) {
        return items.findByCategoryCodeAndCompatibleWith(categoryCode, assetModel);
    }

    public List<RoleKit> kits() {
        return kits.findAll();
    }

    public Optional<RoleKit> findKit(String code) {
        return code == null ? Optional.empty() : kits.findById(code);
    }
}
