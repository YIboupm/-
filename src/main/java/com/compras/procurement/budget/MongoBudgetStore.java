package com.compras.procurement.budget;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** MongoDB: conditional findAndModify on a single document is atomic, which is all the race needs. */
@Component
public class MongoBudgetStore implements BudgetStore {

    private final MongoTemplate mongo;

    public MongoBudgetStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public Optional<BudgetAccount> account(String id) {
        return Optional.ofNullable(mongo.findById(id, BudgetAccount.class));
    }

    @Override
    public void ensure(BudgetAccount account) {
        try {
            mongo.insert(account);
        } catch (DuplicateKeyException exists) {
            // already created, possibly by a concurrent request
        }
    }

    @Override
    public boolean tryReserve(String accountId, long amount) {
        BudgetAccount updated = mongo.findAndModify(
                Query.query(Criteria.where("id").is(accountId).and("available").gte(amount)),
                new Update().inc("available", -amount).inc("reserved", amount),
                FindAndModifyOptions.options().returnNew(true),
                BudgetAccount.class);
        return updated != null;
    }

    @Override
    public void move(String accountId, long available, long reserved, long committed, long spent) {
        mongo.findAndModify(Query.query(Criteria.where("id").is(accountId)),
                new Update().inc("available", available).inc("reserved", reserved).inc("committed", committed)
                        .inc("spent", spent),
                FindAndModifyOptions.options().returnNew(true), BudgetAccount.class);
    }

    @Override
    public boolean insert(BudgetEntry entry) {
        try {
            mongo.insert(entry);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public void delete(String entryId) {
        mongo.remove(Query.query(Criteria.where("id").is(entryId)), BudgetEntry.class);
    }

    @Override
    public List<BudgetEntry> entriesForRequest(String requestId) {
        return sorted(mongo.find(Query.query(Criteria.where("requestId").is(requestId)), BudgetEntry.class));
    }

    @Override
    public List<BudgetEntry> entriesForAccount(String accountId) {
        return sorted(mongo.find(Query.query(Criteria.where("accountId").is(accountId)), BudgetEntry.class));
    }

    @Override
    public List<BudgetAccount> accounts(String period) {
        return mongo.find(Query.query(Criteria.where("period").is(period)), BudgetAccount.class).stream()
                .sorted(Comparator.comparing(BudgetAccount::department)).toList();
    }

    private static List<BudgetEntry> sorted(List<BudgetEntry> entries) {
        return entries.stream().sorted(Comparator.comparing(BudgetEntry::at)).toList();
    }
}
