package com.compras.procurement.policy;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PolicyVersionRepository extends MongoRepository<PolicySet, String> {
}
