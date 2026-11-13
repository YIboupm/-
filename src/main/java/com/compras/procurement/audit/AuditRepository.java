package com.compras.procurement.audit;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuditRepository extends MongoRepository<AuditEntry, String> {
    List<AuditEntry> findTop200ByOrderByAtDesc();

    List<AuditEntry> findByAggregateIdOrderByAtAsc(String aggregateId);
}
