package com.compras.procurement.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CatalogItemRepository extends MongoRepository<CatalogItem, String> {
    List<CatalogItem> findByCategoryCode(String categoryCode);

    /** Matches when {@code model} is one of the values in the compatibleWith array. */
    List<CatalogItem> findByCategoryCodeAndCompatibleWith(String categoryCode, String model);
}
