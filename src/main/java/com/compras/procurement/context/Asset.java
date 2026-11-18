package com.compras.procurement.context;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/**
 * Mock of the IT asset register.
 *
 * @param assignedTo employee id for personal assets, null for shared ones (printers ...)
 * @param status     IN_USE, IDLE (can be reassigned), SHARED (can be used by others) or RESERVED (received for a
 *                   request, not yet handed out: never offered for reuse)
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
        Map<String, Object> attributes,
        /** Manufacturer's serial number, as scanned or typed at receipt (null for the seeded register). */
        String serialNumber,
        /** Where it came from: the request, order and delivery it was received on (null for the seed). */
        Acquisition acquisition
) {
    public static final String IN_USE = "IN_USE";
    public static final String RETURNED = "RETURNED";
    public static final String RESERVED = "RESERVED";

    public record Acquisition(String requestId, String requestNumber, String orderNumber, String supplierName,
                              java.math.BigDecimal unitPrice, String currency, java.time.Instant receivedAt,
                              String receivedBy, String receiptId) {
    }

    public Asset assignTo(String employeeId, String newCustodian) {
        return new Asset(assetTag, type, model, site, location, employeeId, IN_USE, newCustodian, attributes,
                serialNumber, acquisition);
    }
}
