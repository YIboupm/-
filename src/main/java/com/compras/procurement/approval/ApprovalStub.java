package com.compras.procurement.approval;

import com.compras.procurement.request.RequestService;
import com.compras.procurement.shared.Events;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Demo stand-in for the approval workflow. It subscribes to RequestSubmitted and approves at
 * once. The real version would create a task in the company's workflow tool (OA, Feishu,
 * ServiceNow ...) and call {@link RequestService#approve} from that tool's callback; nothing
 * upstream or downstream changes.
 */
@Component
public class ApprovalStub {

    private final ApprovalProperties properties;
    private final RequestService requests;

    public ApprovalStub(ApprovalProperties properties, RequestService requests) {
        this.properties = properties;
        this.requests = requests;
    }

    @EventListener
    public void on(Events.RequestSubmitted event) {
        if (event.nonStandard()) {
            requests.routeToBuyer(event.requestId(), "Special requirements: a buyer will source and price this request");
        } else if (properties.autoApprove()) {
            requests.approve(event.requestId(), "Auto-approved by the demo approval stub");
        }
    }
}
