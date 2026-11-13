package com.compras.procurement.admin;

import com.compras.procurement.audit.AuditEntry;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.consolidation.ConsolidatedOrder;
import com.compras.procurement.consolidation.DemandLine;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.intake.Conversation;
import com.compras.procurement.intake.DraftAssessment;
import com.compras.procurement.intake.DraftEvaluator;
import com.compras.procurement.intake.IntakeProperties;
import com.compras.procurement.request.NewRequest;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.shared.SequenceService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Demo helpers: reset the data and fill the pool with requests "from other employees". */
@Service
public class DemoService {

    /** Shape of seed/demo_requests.json. */
    public record DemoRequest(String employeeId, String categoryCode, int neededInDays, Map<String, Object> fields) {
    }

    private final MongoTemplate mongo;
    private final SeedDataLoader seed;
    private final CatalogService catalog;
    private final EmployeeContextService employees;
    private final DraftEvaluator evaluator;
    private final RequestService requests;
    private final IntakeProperties properties;
    private final Clock clock;

    public DemoService(MongoTemplate mongo, SeedDataLoader seed, CatalogService catalog,
                       EmployeeContextService employees, DraftEvaluator evaluator, RequestService requests,
                       IntakeProperties properties, Clock clock) {
        this.mongo = mongo;
        this.seed = seed;
        this.catalog = catalog;
        this.employees = employees;
        this.evaluator = evaluator;
        this.requests = requests;
        this.properties = properties;
        this.clock = clock;
    }

    /** Clears all transactional data and reloads the reference data from the seed files. */
    public void reset() {
        // remove() keeps the collections and their indexes; drop() would lose the indexes
        mongo.remove(new Query(), Conversation.class);
        mongo.remove(new Query(), PurchaseRequest.class);
        mongo.remove(new Query(), DemandLine.class);
        mongo.remove(new Query(), ConsolidatedOrder.class);
        mongo.remove(new Query(), AuditEntry.class);
        mongo.remove(new Query(), SequenceService.Counter.class);
        seed.loadReferenceData();
    }

    /**
     * Submits the sample requests through the same path as the chat (resolve, validate, submit,
     * approve, pool). The pipeline does not care which channel a request came from.
     */
    public List<String> loadSampleRequests() {
        LocalDate today = LocalDate.now(clock);
        return seed.read("seed/demo_requests.json", DemoRequest.class).stream().map(d -> {
            EmployeeContext context = employees.contextFor(d.employeeId());
            CategoryDefinition category = catalog.category(d.categoryCode());
            Map<String, Object> fields = new LinkedHashMap<>(d.fields());
            fields.putIfAbsent("deliverySite", context.employee().site());
            fields.putIfAbsent("urgent", false);
            fields.putIfAbsent("chargeTo", context.employee().department());
            fields.put("neededBy", today.plusDays(d.neededInDays()).toString());
            Map<String, String> sources = new LinkedHashMap<>();
            fields.keySet().forEach(k -> sources.put(k, "STATED"));
            DraftAssessment a = evaluator.assess(category, fields, context);
            if (!a.readyToSubmit()) {
                throw new IllegalStateException("Sample request for " + d.employeeId() + " is incomplete: "
                        + a.missingFields() + " " + a.violations());
            }
            return requests.submit(new NewRequest("demo-loader", null, context, category, fields, sources,
                    a.lines(), a.estimatedTotal(), properties.currency(), a.warnings(), List.of(), List.of(),
                    null, null)).getNumber();
        }).toList();
    }
}
