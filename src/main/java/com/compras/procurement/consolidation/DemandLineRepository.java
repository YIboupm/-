package com.compras.procurement.consolidation;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface DemandLineRepository extends MongoRepository<DemandLine, String> {
    List<DemandLine> findByStatus(DemandLine.Status status);

    List<DemandLine> findByRequestId(String requestId);

    boolean existsByRequestIdAndStatus(String requestId, DemandLine.Status status);
}
