package com.compras.procurement.shared;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/** Human-readable document numbers (PR-2026-00001, PO-2026-00001) backed by an atomic counter. */
@Service
public class SequenceService {

    @Document("counters")
    public record Counter(@Id String id, long seq) {
    }

    private final MongoTemplate mongo;
    private final Clock clock;

    public SequenceService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public String nextNumber(String prefix) {
        int year = LocalDate.now(clock).getYear();
        String key = prefix + "-" + year;
        Counter counter = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(key)),
                new Update().inc("seq", 1L),
                FindAndModifyOptions.options().returnNew(true).upsert(true),
                Counter.class);
        long seq = counter == null ? 1L : counter.seq();
        return String.format("%s-%d-%05d", prefix, year, seq);
    }
}
