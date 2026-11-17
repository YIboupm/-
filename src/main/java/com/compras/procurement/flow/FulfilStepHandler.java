package com.compras.procurement.flow;

import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import org.springframework.stereotype.Component;

/**
 * FULFIL: the request is approved and handed to ordering (pooled with other requests, ordered at once, or issued as
 * a call-off, depending on what it is).
 */
@Component
public class FulfilStepHandler implements StepHandler {

    private final RequestService requests;

    public FulfilStepHandler(RequestService requests) {
        this.requests = requests;
    }

    @Override
    public String type() {
        return "FULFIL";
    }

    @Override
    public Outcome enter(Context c) {
        PurchaseRequest r = c.request();
        boolean over = "OVER_BUDGET".equals(r.getBudgetCheck());
        String note = r.getApprovals().isEmpty()
                ? "Auto-approved: catalog item within the approval limit and the department's budget"
                : "Approved by " + String.join(", ", r.getApprovals().stream().filter(ApprovalStep::isApproved)
                        .map(ApprovalStep::approverName).toList());
        requests.approve(r.getId(), note + (over ? "; over budget, accepted by finance" : "; budget reserved"));
        return Outcome.DONE;
    }
}
