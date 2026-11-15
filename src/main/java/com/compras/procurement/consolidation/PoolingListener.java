package com.compras.procurement.consolidation;

import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.shared.Events;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Subscribes to approvals: approved lines enter the pool; urgent ones are ordered straight away. */
@Component
public class PoolingListener {

    private final RequestService requests;
    private final DemandLineRepository lines;
    private final ConsolidationService consolidation;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PoolingListener(RequestService requests, DemandLineRepository lines, ConsolidationService consolidation,
                           ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.lines = lines;
        this.consolidation = consolidation;
        this.events = events;
        this.clock = clock;
    }

    @EventListener
    public void on(Events.RequestApproved event) {
        PurchaseRequest request = requests.get(event.requestId());
        if (request.isNonStandard() || request.getSourced() != null) {
            // sourced individually (buyer, or the category manager's RFQ): its own order, never pooled
            consolidation.issueSpecial(request.getId());
            return;
        }
        if (com.compras.procurement.request.SpendProfile.isRecurring(request.getAttributes())) {
            consolidation.issueCallOff(request.getId()); // cloud under the enterprise agreement: nothing to pool
            return;
        }
        Instant now = Instant.now(clock);
        String department = request.getChargeTo() != null ? request.getChargeTo().department()
                : request.getRequester().department();
        List<DemandLine> pooled = lines.saveAll(request.getLines().stream()
                .map(l -> new DemandLine(request.getId(), request.getNumber(), request.getRequester().name(),
                        department, l.sku(), l.name(), l.supplierId(),
                        request.getDeliverySite(), l.quantity(), request.getNeededBy(), request.isUrgent(), now))
                .toList());
        requests.markPooled(request.getId());
        events.publishEvent(new Events.DemandPooled(request.getId(), pooled.stream().map(DemandLine::getId).toList(), now));
        if (request.isUrgent()) {
            consolidation.releaseUrgent(request.getId());
        }
    }

    /** A cancelled request leaves the pool (lines already on an order stay there). */
    @EventListener
    public void on(Events.RequestCancelled event) {
        List<DemandLine> open = lines.findByRequestId(event.requestId()).stream()
                .filter(l -> l.getStatus() == DemandLine.Status.POOLED).toList();
        lines.deleteAll(open);
    }
}
