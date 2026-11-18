package com.compras.procurement.context;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AssetRepository extends MongoRepository<Asset, String> {
    List<Asset> findByAssignedTo(String employeeId);

    List<Asset> findBySiteAndAssignedToIsNull(String site);

    boolean existsBySerialNumberAndStatusNot(String serialNumber, String status);

    @org.springframework.data.mongodb.repository.Query("{'_id': ?0, 'status': {'$ne': 'RETURNED'}}")
    @org.springframework.data.mongodb.repository.Update("{'$set': {'status': 'RETURNED', 'assignedTo': null, 'location': 'Returned to supplier'}}")
    long markReturned(String assetTag);

    @org.springframework.data.mongodb.repository.Query("{'_id': ?0, 'status': {'$in':['RESERVED','IDLE']}}")
    @org.springframework.data.mongodb.repository.Update("{'$set': {'status':'IN_USE','assignedTo':?1,'location':?2,'custodian':?3}}")
    long assignAvailable(String assetTag, String employeeId, String location, String custodian);
}
