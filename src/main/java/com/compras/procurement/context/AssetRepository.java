package com.compras.procurement.context;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AssetRepository extends MongoRepository<Asset, String> {
    List<Asset> findByAssignedTo(String employeeId);

    List<Asset> findBySiteAndAssignedToIsNull(String site);

    boolean existsBySerialNumber(String serialNumber);
}
