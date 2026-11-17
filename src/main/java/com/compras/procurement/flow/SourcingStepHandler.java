package com.compras.procurement.flow;

import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.StrategicCase;
import org.springframework.stereotype.Component;

/** SOURCING: a buyer, or the category manager, collects quotes and awards (policy 5). Finished by the award. */
@Component
public class SourcingStepHandler implements StepHandler {

    private final RequestService requests;

    public SourcingStepHandler(RequestService requests) {
        this.requests = requests;
    }

    @Override
    public String type() {
        return "SOURCING";
    }

    @Override
    public Outcome enter(Context c) {
        PurchaseRequest r = c.request();
        if ("CATEGORY_MANAGER".equals(c.step().owner())) {
            StrategicCase s = r.getStrategic();
            String who = s == null || s.decidedBy() == null ? "The category manager" : "Category manager " + s.decidedBy();
            String text = s == null || s.note() == null || s.note().isBlank() ? "" : ": " + s.note();
            requests.startSourcing(r.getId(), "CATEGORY_MANAGER", s != null && "NEW_FRAMEWORK".equals(s.outcome())
                    ? who + " negotiates a framework agreement; this request is the first call-off. Follow-up: add it "
                    + "to the catalog once signed" + text
                    : who + " runs quotes / a tender" + text);
        } else {
            String by = r.getApprovals().stream().filter(ApprovalStep::isApproved).reduce((a, b) -> b)
                    .map(a -> "Need approved by " + a.approverName() + ": ").orElse("");
            requests.routeToBuyer(r.getId(), by + "a buyer will source and price it");
        }
        return Outcome.WAITING;
    }

    @Override
    public Outcome resume(Context c) {
        return c.request().getSourced() != null ? Outcome.DONE : Outcome.WAITING;
    }
}
