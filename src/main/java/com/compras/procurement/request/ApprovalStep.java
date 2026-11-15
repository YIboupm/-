package com.compras.procurement.request;

import java.time.Instant;

/**
 * One sign-off in a request's approval chain, from the approval matrix.
 *
 * @param stage  NEED (is the need justified? special requests, before sourcing) or SPEND (may we spend this amount?)
 * @param reason why this approver is in the chain, e.g. "Over €5,000" or "Over the department's budget"
 * @param status PENDING, APPROVED or REJECTED; steps are decided in order
 */
public record ApprovalStep(String role, String approverId, String approverName, String approverTitle, String reason,
                           String stage, String status, Instant decidedAt, String note) {

    public static ApprovalStep pending(String role, String approverId, String approverName, String approverTitle,
                                       String reason, String stage) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage, "PENDING", null, null);
    }

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    /** The approver sent it to strategic review instead of deciding (policy 6). */
    public ApprovalStep escalate(String note, Instant at) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage, "ESCALATED", at, note);
    }

    public ApprovalStep decide(boolean approved, String note, Instant at) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage,
                approved ? "APPROVED" : "REJECTED", at, note);
    }
}
