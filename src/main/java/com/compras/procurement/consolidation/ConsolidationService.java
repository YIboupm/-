package com.compras.procurement.consolidation;

import com.compras.procurement.catalog.CatalogItem;
import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.shared.Events;
import com.compras.procurement.shared.Money;
import com.compras.procurement.shared.SequenceService;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs the demand pool: previews groups, releases them into consolidated purchase orders and
 * moves the underlying requests forward.
 *
 * Concurrency: a line is claimed with an atomic conditional update (status POOLED -> ORDERED).
 * If two runs overlap, each line still ends up on exactly one order, without needing
 * multi-document transactions.
 */
@Service
public class ConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationService.class);

    private final DemandLineRepository lines;
    private final ConsolidatedOrderRepository orders;
    private final ConsolidationPlanner planner;
    private final CatalogService catalog;
    private final RequestService requests;
    private final PurchaseOrderGateway gateway;
    private final SequenceService sequences;
    private final MongoTemplate mongo;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ConsolidationService(DemandLineRepository lines, ConsolidatedOrderRepository orders,
                                ConsolidationPlanner planner, CatalogService catalog, RequestService requests,
                                PurchaseOrderGateway gateway, SequenceService sequences, MongoTemplate mongo,
                                ApplicationEventPublisher events, Clock clock) {
        this.lines = lines;
        this.orders = orders;
        this.planner = planner;
        this.catalog = catalog;
        this.requests = requests;
        this.gateway = gateway;
        this.sequences = sequences;
        this.mongo = mongo;
        this.events = events;
        this.clock = clock;
    }

    /** What would happen if the scheduler ran on {@code asOf}. No side effects. */
    public List<GroupDecision> preview(LocalDate asOf) {
        return planner.plan(lines.findByStatus(DemandLine.Status.POOLED), itemsBySku(), asOf);
    }

    /** Daily automatic run. */
    @Scheduled(cron = "${procurement.consolidation.cron}")
    public void scheduledRun() {
        RunReport report = run(LocalDate.now(clock));
        log.info("Scheduled consolidation: {} order(s), savings {}", report.ordersCreated(), report.savings());
    }

    /** Applies the policies as of {@code asOf} and releases every group that qualifies. */
    public RunReport run(LocalDate asOf) {
        List<GroupDecision> result = new ArrayList<>();
        BigDecimal savings = BigDecimal.ZERO;
        int created = 0;
        for (GroupDecision decision : preview(asOf)) {
            if (decision.release()) {
                ConsolidatedOrder order = release(decision, asOf);
                if (order != null) {
                    created++;
                    savings = savings.add(order.savings());
                    decision = decision.withOrderNumber(order.number());
                }
            }
            result.add(decision);
        }
        return new RunReport(asOf, result, created, Money.scale(savings));
    }

    /**
     * Urgent requests do not wait for the pool. The order also takes every pooled line with the same
     * item, supplier and site: those requesters simply get their goods earlier, and the larger quantity
     * may reach a better price tier instead of splitting the same item over two orders in one week.
     */
    public void releaseUrgent(String requestId) {
        LocalDate today = LocalDate.now(clock);
        Map<String, CatalogItem> items = itemsBySku();
        List<DemandLine> allPooled = lines.findByStatus(DemandLine.Status.POOLED);
        allPooled.stream()
                .filter(l -> l.getRequestId().equals(requestId))
                .map(DemandLine::groupKey)
                .distinct()
                .forEach(key -> {
                    CatalogItem item = items.get(key.sku());
                    List<DemandLine> group = allPooled.stream().filter(l -> l.groupKey().equals(key)).toList();
                    if (item != null) {
                        release(planner.urgent(new PoolGroup(key, item, group), today), today);
                    }
                });
    }

    /** A special request the buyer has sourced becomes its own purchase order, outside the pool. */
    public ConsolidatedOrder issueSpecial(String requestId) {
        PurchaseRequest request = requests.get(requestId);
        PurchaseRequest.SourcedItem s = request.getSourced();
        if (s == null) {
            throw new IllegalStateException("Request " + request.getNumber() + " has no sourced item.");
        }
        LocalDate today = LocalDate.now(clock);
        String department = request.getChargeTo() != null ? request.getChargeTo().department()
                : request.getRequester().department();
        ConsolidatedOrder order = orders.save(new ConsolidatedOrder(new ObjectId().toHexString(),
                sequences.nextNumber("PO"), "SPECIAL", s.description(), null, s.supplierName(),
                request.getDeliverySite(), s.quantity(), s.unitPrice(), s.unitPrice(), s.total(), s.total(),
                BigDecimal.ZERO.setScale(2), request.getCurrency(), List.of(ReleaseReason.SPECIAL_REQUEST),
                List.of(new ConsolidatedOrder.Allocation(null, request.getId(), request.getNumber(),
                        request.getRequester().name(), department, s.quantity(), request.getNeededBy(),
                        s.unitPrice(), 0)),
                today, Instant.now(clock)));
        gateway.issue(order);
        events.publishEvent(new Events.OrderReleased(order.id(), order.number(), order.sku(), order.totalQuantity(),
                List.of(ReleaseReason.SPECIAL_REQUEST.name()), Instant.now(clock)));
        requests.markOrdered(requestId, List.of(order.number()), s.total());
        return order;
    }

    /**
     * A call-off under a framework agreement (cloud): the order tells the provider/cloud team to provision; the
     * amount is the estimated contract value. The budget is charged for the current quarter's share only.
     */
    public ConsolidatedOrder issueCallOff(String requestId) {
        PurchaseRequest request = requests.get(requestId);
        PurchaseRequest.Line line = request.getLines().get(0);
        LocalDate today = LocalDate.now(clock);
        String department = request.chargeDepartment();
        ConsolidatedOrder order = orders.save(new ConsolidatedOrder(new ObjectId().toHexString(),
                sequences.nextNumber("PO"), line.sku(), line.name(), line.supplierId(), line.supplierName(),
                request.getDeliverySite(), line.quantity(), line.estimatedUnitPrice(), line.estimatedUnitPrice(),
                line.estimatedTotal(), line.estimatedTotal(), BigDecimal.ZERO.setScale(2), request.getCurrency(),
                List.of(ReleaseReason.FRAMEWORK_CALL_OFF),
                List.of(new ConsolidatedOrder.Allocation(null, request.getId(), request.getNumber(),
                        request.getRequester().name(), department, line.quantity(), request.getNeededBy(),
                        line.estimatedUnitPrice(), 0)),
                today, Instant.now(clock)));
        gateway.issue(order);
        events.publishEvent(new Events.OrderReleased(order.id(), order.number(), order.sku(), order.totalQuantity(),
                List.of(ReleaseReason.FRAMEWORK_CALL_OFF.name()), Instant.now(clock)));
        requests.markOrdered(requestId, List.of(order.number()),
                com.compras.procurement.request.SpendProfile.quarterCharge(request, today));
        return order;
    }

    public List<ConsolidatedOrder> orders() {
        return orders.findAllByOrderByCreatedAtDesc();
    }

    // ------------------------------------------------------------------ internals

    private ConsolidatedOrder release(GroupDecision decision, LocalDate asOf) {
        String orderId = new ObjectId().toHexString();
        String number = sequences.nextNumber("PO");

        List<DemandLine> claimed = decision.lines().stream()
                .map(l -> claim(l.getId(), orderId, number))
                .filter(Objects::nonNull)
                .toList();
        if (claimed.isEmpty()) {
            return null; // another run took these lines first
        }

        CatalogItem item = catalog.item(decision.key().sku());
        int qty = claimed.stream().mapToInt(DemandLine::getQuantity).sum();
        BigDecimal unit = item.unitPriceFor(qty);
        BigDecimal total = Money.times(unit, qty);
        List<ConsolidatedOrder.Allocation> allocations = claimed.stream().map(l -> new ConsolidatedOrder.Allocation(
                l.getId(), l.getRequestId(), l.getRequestNumber(), l.getRequesterName(), l.getDepartment(),
                l.getQuantity(), l.getNeededBy(), item.unitPriceFor(l.getQuantity()),
                Math.max(0, ChronoUnit.DAYS.between(LocalDate.ofInstant(l.getPooledAt(), clock.getZone()), asOf))))
                .toList();
        BigDecimal individual = allocations.stream()
                .map(a -> Money.times(a.individualUnitPrice(), a.quantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ConsolidatedOrder order = orders.save(new ConsolidatedOrder(orderId, number, item.sku(), item.name(),
                item.supplierId(), item.supplierName(), decision.key().deliverySite(), qty, unit, item.listPrice(),
                total, Money.scale(individual), Money.scale(individual.subtract(total)), item.currency(),
                decision.reasons(), allocations, asOf, Instant.now(clock)));

        gateway.issue(order);
        events.publishEvent(new Events.OrderReleased(order.id(), order.number(), order.sku(), qty,
                decision.reasons().stream().map(Enum::name).toList(), Instant.now(clock)));
        advanceRequests(claimed);
        return order;
    }

    /** Atomic: only a line that is still POOLED can be taken. Returns null if someone else took it. */
    private DemandLine claim(String lineId, String orderId, String orderNumber) {
        return mongo.findAndModify(
                Query.query(Criteria.where("id").is(lineId).and("status").is(DemandLine.Status.POOLED)),
                new Update().set("status", DemandLine.Status.ORDERED).set("orderId", orderId).set("orderNumber", orderNumber),
                FindAndModifyOptions.options().returnNew(true),
                DemandLine.class);
    }

    /** A request becomes ORDERED once none of its lines is left in the pool. */
    private void advanceRequests(List<DemandLine> claimed) {
        Set<String> requestIds = claimed.stream().map(DemandLine::getRequestId).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String requestId : requestIds) {
            if (!lines.existsByRequestIdAndStatus(requestId, DemandLine.Status.POOLED)) {
                List<DemandLine> requestLines = lines.findByRequestId(requestId);
                List<String> numbers = requestLines.stream()
                        .map(DemandLine::getOrderNumber).filter(Objects::nonNull).distinct().toList();
                // what this request really costs: its quantities at the price of the order each line landed on
                BigDecimal actual = requestLines.stream()
                        .filter(l -> l.getOrderId() != null)
                        .map(l -> orders.findById(l.getOrderId())
                                .map(o -> Money.times(o.unitPrice(), l.getQuantity())).orElse(BigDecimal.ZERO))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                requests.markOrdered(requestId, numbers, Money.scale(actual));
            }
        }
    }

    private Map<String, CatalogItem> itemsBySku() {
        return catalog.allItems().stream().collect(Collectors.toMap(CatalogItem::sku, Function.identity()));
    }
}
