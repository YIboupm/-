package com.compras.procurement.request;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PurchaseRequestRepository extends MongoRepository<PurchaseRequest, String> {
    List<PurchaseRequest> findAllByOrderBySubmittedAtDesc();

    List<PurchaseRequest> findByStatusOrderBySubmittedAtAsc(RequestStatus status);
}
