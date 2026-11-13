package com.compras.procurement.consolidation;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ConsolidatedOrderRepository extends MongoRepository<ConsolidatedOrder, String> {
    List<ConsolidatedOrder> findAllByOrderByCreatedAtDesc();
}
