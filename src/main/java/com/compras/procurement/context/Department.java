package com.compras.procurement.context;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Mock of the finance master data: which cost centre a department's purchases are charged to. */
@Document("departments")
public record Department(@Id String name, String costCenter) {
}
