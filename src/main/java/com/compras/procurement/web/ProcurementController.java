package com.compras.procurement.web;

import com.compras.procurement.admin.DemoService;
import com.compras.procurement.audit.AuditEntry;
import com.compras.procurement.audit.AuditService;
import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.RoleKit;
import com.compras.procurement.consolidation.ConsolidatedOrder;
import com.compras.procurement.consolidation.ConsolidationService;
import com.compras.procurement.consolidation.GroupDecision;
import com.compras.procurement.consolidation.RunReport;
import com.compras.procurement.context.Employee;
import com.compras.procurement.context.EmployeeContextService;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Read models for the UI plus the demo controls. */
@RestController
@RequestMapping("/api")
public class ProcurementController {

    private final EmployeeContextService employees;
    private final CatalogService catalog;
    private final RequestService requests;
    private final ConsolidationService consolidation;
    private final AuditService audit;
    private final DemoService demo;
    private final Clock clock;

    public ProcurementController(EmployeeContextService employees, CatalogService catalog, RequestService requests,
                                 ConsolidationService consolidation, AuditService audit, DemoService demo, Clock clock) {
        this.employees = employees;
        this.catalog = catalog;
        this.requests = requests;
        this.consolidation = consolidation;
        this.audit = audit;
        this.demo = demo;
        this.clock = clock;
    }

    // ---- reference data

    @GetMapping("/employees")
    public List<Employee> employees() {
        return employees.allEmployees();
    }

    @GetMapping("/categories")
    public List<CategoryDefinition> categories() {
        return catalog.intakeCategories();
    }

    @GetMapping("/catalog")
    public List<CatalogItem> catalog() {
        return catalog.allItems();
    }

    @GetMapping("/kits")
    public List<RoleKit> kits() {
        return catalog.kits();
    }

    // ---- requests

    @GetMapping("/requests")
    public List<PurchaseRequest> requests() {
        return requests.all();
    }

    @GetMapping("/requests/{id}")
    public PurchaseRequest request(@PathVariable String id) {
        return requests.get(id);
    }

    // ---- consolidation

    @GetMapping("/consolidation/pool")
    public List<GroupDecision> pool(@RequestParam(required = false)
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return consolidation.preview(asOf == null ? LocalDate.now(clock) : asOf);
    }

    @PostMapping("/consolidation/run")
    public RunReport run(@RequestParam(required = false)
                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return consolidation.run(asOf == null ? LocalDate.now(clock) : asOf);
    }

    @GetMapping("/consolidation/orders")
    public List<ConsolidatedOrder> orders() {
        return consolidation.orders();
    }

    // ---- audit

    @GetMapping("/audit")
    public List<AuditEntry> audit() {
        return audit.recent();
    }

    // ---- demo controls

    @PostMapping("/demo/reset")
    public Map<String, String> reset() {
        demo.reset();
        return Map.of("status", "Demo data reset");
    }

    @PostMapping("/demo/sample-requests")
    public Map<String, Object> sampleRequests() {
        return Map.of("submitted", demo.loadSampleRequests());
    }

    @GetMapping("/demo/today")
    public Map<String, Object> today() {
        return Map.of("today", LocalDate.now(clock).toString());
    }
}
