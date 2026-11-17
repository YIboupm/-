package com.compras.procurement.approval;

import com.compras.procurement.request.ApprovalStep;
import com.compras.procurement.request.PurchaseRequest;
import com.compras.procurement.request.RequestService;
import com.compras.procurement.request.RequestStatus;
import com.compras.procurement.shared.Events;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The approvers' side of a request: deciding a step of the chain, or escalating it to strategic review. Who is in
 * the chain comes from the policy's approval rules ({@link ApprovalPolicy}); what happens when the chain completes
 * is the request's route ({@link com.compras.procurement.flow.FlowEngine}).
 */
@Service
public class ApprovalService {

    private final RequestService requests;
    private final com.compras.procurement.flow.FlowEngine flow;
    private final StaffRepository staff;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ApprovalService(RequestService requests, com.compras.procurement.flow.FlowEngine flow,
                           StaffRepository staff, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.flow = flow;
        this.staff = staff;
        this.events = events;
        this.clock = clock;
    }

    /**
     * An approver sends the request to strategic review instead of approving or rejecting it (policy 6/7).
     * The step is marked ESCALATED; nothing was reserved yet.
     */
    public PurchaseRequest escalate(String requestId, String staffId, String reason) {
        PurchaseRequest r = requests.get(requestId);
        ApprovalStep step = r.currentStep();
        if (r.getStatus() != RequestStatus.PENDING_APPROVAL || step == null || !step.approverId().equals(staffId)) {
            throw new IllegalStateException(r.getNumber() + " is not waiting for your approval.");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Say why it needs a strategic review.");
        }
        List<ApprovalStep> chain = new ArrayList<>(r.getApprovals());
        chain.set(chain.indexOf(step), step.escalate(reason.trim(), Instant.now(clock)));
        requests.updateApprovals(requestId, chain);
        return flow.detour(requestId, "MANUAL", List.of(reason.trim()),
                step.approverName() + " (" + StaffRole.valueOf(step.role()).label() + ")");
    }

    /** The current approver decides their step. */
    public PurchaseRequest decide(String requestId, String staffId, boolean approve, String note) {
        PurchaseRequest r = requests.get(requestId);
        ApprovalStep step = r.currentStep();
        if (r.getStatus() != RequestStatus.PENDING_APPROVAL || step == null) {
            throw new IllegalStateException(r.getNumber() + " is not waiting for an approval.");
        }
        if (!step.approverId().equals(staffId)) {
            throw new IllegalStateException(r.getNumber() + " is waiting for " + step.approverName() + ", not you.");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new IllegalStateException("Give the employee a reason for the rejection.");
        }
        Instant now = Instant.now(clock);
        List<ApprovalStep> chain = new ArrayList<>(r.getApprovals());
        chain.set(chain.indexOf(step), step.decide(approve, note, now));
        r = requests.updateApprovals(requestId, chain);
        events.publishEvent(new Events.ApprovalDecided(r.getId(), r.getNumber(), step.approverName(), step.role(),
                approve, note, now));

        if (!approve) {
            return requests.reject(requestId, "Rejected by " + step.approverName() + " ("
                    + StaffRole.valueOf(step.role()).label() + "): " + note.trim());
        }
        if (r.currentStep() != null) {
            return r; // next approver
        }
        return flow.resume(requestId); // the chain is complete: the route continues
    }

    /** Requests waiting for this person. */
    public List<PurchaseRequest> inbox(String staffId) {
        return requests.all().stream()
                .filter(r -> r.getStatus() == RequestStatus.PENDING_APPROVAL)
                .filter(r -> r.currentStep() != null && r.currentStep().approverId().equals(staffId))
                .toList();
    }

    public List<StaffMember> staff() {
        return staff.findAll();
    }

    public StaffMember member(String staffId) {
        return staff.findById(staffId == null ? "" : staffId)
                .orElseThrow(() -> new IllegalStateException("Unknown staff member"));
    }
}
