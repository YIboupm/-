package com.compras.procurement.receiving;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ReceiptRepository extends MongoRepository<Receipt, String> {

    Optional<Receipt> findByOrderId(String orderId);

    List<Receipt> findByLinesRequestId(String requestId);
}
