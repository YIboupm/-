package com.compras.procurement.approval;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface StaffRepository extends MongoRepository<StaffMember, String> {
}
