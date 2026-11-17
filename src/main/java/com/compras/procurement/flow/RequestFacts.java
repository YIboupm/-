package com.compras.procurement.flow;

import com.compras.procurement.approval.ApprovalPolicy;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.policy.Facts;
import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.SpendProfile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** What the rules get to see of a request: the single place that turns a request into facts. */
@Component
public class RequestFacts {

    private final CatalogService catalog;
    private final Clock clock;

    public RequestFacts(CatalogService catalog, Clock clock) {
        this.catalog = catalog;
        this.clock = clock;
    }

    public static boolean catalogItem(PurchaseRequest r) {
        return !r.isNonStandard() && r.getSourced() == null;
    }

    public boolean frameworkCategory(PurchaseRequest r) {
        return catalog.findCategory(r.getCategoryCode() == null ? "" : r.getCategoryCode())
                .map(c -> c.resolution() == ResolutionStrategy.CONSUMPTION).orElse(false);
    }

    /** For the routing table, when the request is submitted. */
    public Map<String, Object> routing(PurchaseRequest r) {
        return Facts.of(r.isNonStandard(), catalogItem(r), frameworkCategory(r), r.matrixValue(), r.getCategoryCode(),
                r.chargeDepartment(), false, r.getAttributes());
    }

    /** For the routing table once a price is known: only the value can still change the route. */
    public Map<String, Object> routingAfterAward(PurchaseRequest r) {
        return Facts.of(false, false, false, r.matrixValue(), r.getCategoryCode(), r.chargeDepartment(), false,
                Map.of());
    }

    /** For the approval tables. */
    public ApprovalPolicy.Input approval(PurchaseRequest r, String stage, boolean overBudget,
                                         List<ApprovalStep> decided) {
        return new ApprovalPolicy.Input(stage, catalogItem(r), r.matrixValue(), r.getCategoryCode(),
                r.chargeDepartment(), r.getRequester().name(), overBudget, decided, r.getAttributes());
    }

    /** The current quarter's share: all of it for a purchase, the remaining months of it for a cloud call-off. */
    public BigDecimal quarterCharge(PurchaseRequest r) {
        return SpendProfile.quarterCharge(r, LocalDate.now(clock));
    }
}
