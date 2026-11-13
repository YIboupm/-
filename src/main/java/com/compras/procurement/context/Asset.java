package com.compras.procurement.context;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Mock of the IT asset register.
 *
 * @param assignedTo employee id for personal assets, null for shared ones (printers ...)
 */
@Document("assets")
public record Asset(
        @Id String assetTag,
        String type,
        String model,
        String site,
        String location,
        String assignedTo
) {
}
