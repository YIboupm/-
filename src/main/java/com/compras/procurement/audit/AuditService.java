package com.compras.procurement.audit;

import com.compras.procurement.shared.DomainEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Writes every domain event (and every model call, via {@link #record}) to the audit log. */
@Service
public class AuditService {

    private final AuditRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @EventListener
    public void on(DomainEvent event) {
        Map<String, Object> payload = objectMapper.convertValue(event, new TypeReference<Map<String, Object>>() {
        });
        record(event.type(), event.aggregateId(), payload);
    }

    public void record(String type, String aggregateId, Map<String, Object> payload) {
        repository.save(new AuditEntry(null, type, aggregateId, payload, Instant.now(clock)));
    }

    public List<AuditEntry> recent() {
        return repository.findTop200ByOrderByAtDesc();
    }

    public List<AuditEntry> forAggregate(String aggregateId) {
        return repository.findByAggregateIdOrderByAtAsc(aggregateId);
    }
}
