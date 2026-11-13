package com.compras.procurement.catalog;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface CategoryRepository extends MongoRepository<CategoryDefinition, String> {
    List<CategoryDefinition> findByIntakeEnabledTrue();
}
