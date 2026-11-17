package com.compras.procurement.flow;

import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import org.springframework.stereotype.Component;

/** STRATEGIC_REVIEW: the category manager decides how the purchase is sourced (policy 7). */
@Component
public class StrategicReviewStepHandler implements StepHandler {

    private final RequestService requests;

    public StrategicReviewStepHandler(RequestService requests) {
        this.requests = requests;
    }

    @Override
    public String type() {
        return "STRATEGIC_REVIEW";
    }

    @Override
    public Outcome enter(Context c) {
        PurchaseRequest r = c.request();
        if (r.getStatus() != RequestStatus.STRATEGIC_REVIEW) {
            requests.toStrategicReview(r.getId(), "AUTO", r.getRouteReasons(), "Procurement policy");
        }
        return Outcome.WAITING;
    }

    @Override
    public Outcome resume(Context c) {
        PurchaseRequest r = c.request();
        return r.getStrategic() != null && r.getStrategic().outcome() != null ? Outcome.DONE : Outcome.WAITING;
    }
}
