package com.compras.procurement.budget;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

/** Single-document CAS works on standalone MongoDB; balance and audit trail cannot diverge. */
@Component
public class MongoBudgetStore implements BudgetStore {
    private final MongoTemplate mongo;
    public MongoBudgetStore(MongoTemplate mongo) { this.mongo = mongo; }
    @Override public Optional<BudgetAccount> account(String id) {
        BudgetAccount a = mongo.findById(id, BudgetAccount.class);
        if (a != null && a.journal() == null) {
            // Upgrade without moving money. Stop old application instances before upgrading.
            // Keep the legacy collection for audit/recovery; never merge it back after migration.
            var entries = mongo.find(Query.query(Criteria.where("accountId").is(id)), BudgetEntry.class);
            mongo.updateFirst(Query.query(Criteria.where("id").is(id).and("journal").is(null)),
                    new Update().set("journal", entries).set("revision", 0L), BudgetAccount.class);
            a = mongo.findById(id, BudgetAccount.class);
        }
        return Optional.ofNullable(a);
    }
    @Override public void ensure(BudgetAccount account) {
        try { mongo.insert(account); } catch (DuplicateKeyException exists) { /* concurrent creation */ }
    }
    @Override public boolean compareAndSet(BudgetAccount expected, BudgetAccount updated) {
        return mongo.findAndReplace(Query.query(Criteria.where("id").is(expected.id())
                .and("revision").is(expected.revision())), updated) != null;
    }
    @Override public List<BudgetEntry> entriesForRequest(String requestId) {
        var ids = new java.util.LinkedHashSet<String>();
        mongo.find(Query.query(Criteria.where("journal.requestId").is(requestId)), BudgetAccount.class)
                .forEach(a -> ids.add(a.id()));
        mongo.find(Query.query(Criteria.where("requestId").is(requestId)), BudgetEntry.class)
                .forEach(e -> ids.add(e.accountId()));
        return ids.stream().flatMap(id -> entriesForAccount(id).stream())
                .filter(e -> e.requestId().equals(requestId)).toList();
    }
    @Override public List<BudgetEntry> entriesForAccount(String id) {
        return account(id).map(BudgetAccount::journal).orElse(List.of());
    }
    @Override public List<BudgetAccount> accounts(String period) {
        return mongo.find(Query.query(Criteria.where("period").is(period)), BudgetAccount.class).stream()
                .map(a -> account(a.id()).orElseThrow())
                .sorted(java.util.Comparator.comparing(BudgetAccount::department)).toList();
    }
}
