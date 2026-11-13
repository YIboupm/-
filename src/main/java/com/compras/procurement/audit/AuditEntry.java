package com.compras.procurement.audit;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/** Append-only log of domain events and model calls. */
@Document("audit_log")
public record AuditEntry(
        @Id String id,
        String type,
        @Indexed String aggregateId,
        Map<String, Object> payload,
        Instant at
) {
}
