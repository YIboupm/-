package com.compras.procurement.context;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/**
 * Mock of the IT asset register.
 *
 * @param assignedTo employee id for personal assets, null for shared ones (printers ...)
 * @param status     IN_USE, IDLE (can be reassigned) or SHARED (can be used by others)
 * @param custodian  who approves reuse of the asset (e.g. "IT Service Desk")
 * @param attributes comparable properties in the same vocabulary as the catalog (os, ramGb, gpuType ...)
 */
@Document("assets")
public record Asset(
        @Id String assetTag,
        String type,
        String model,
        String site,
        String location,
        String assignedTo,
        String status,
        String custodian,
        Map<String, Object> attributes
) {
}
