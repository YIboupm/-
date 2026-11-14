package com.compras.procurement.fulfillment;

import java.time.Instant;
import java.util.Map;

/**
 * Something the company already has that might meet a need without buying: an idle laptop, a shared
 * server. {@code attributes} uses the catalog vocabulary so the same requirement check applies.
 *
 * @param live true when the data is measured right now (the Mac monitor), false for register data
 */
public record CompanyResource(String id, String name, String type, String status, String location,
                              String custodian, String access, Map<String, Object> attributes,
                              boolean live, Instant observedAt, String note) {

    public boolean busy() {
        return "CURRENTLY_BUSY".equals(status);
    }
}
