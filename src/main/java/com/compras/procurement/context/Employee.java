package com.compras.procurement.context;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Mock of the HR directory. In production this would be read from the HR system / identity provider. */
@Document("employees")
public record Employee(
        @Id String id,
        String name,
        String email,
        String jobRole,
        String department,
        String costCenter,
        String site,
        String managerName
) {
}
