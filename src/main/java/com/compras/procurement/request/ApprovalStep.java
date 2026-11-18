package com.compras.procurement.request;

import java.time.Instant;

/**
 * One sign-off in a request's approval chain, from the approval matrix.
 *
 * @param stage  NEED (is the need justified? special requests, before sourcing) or SPEND (may we spend this amount?)
 * @param reason why this approver is in the chain, e.g. "Over €5,000" or "Over the department's budget"
 * @param status PENDING, APPROVED or REJECTED; steps are decided in order
 * @param rules  ids of the policy rules that put this approver in the chain (null on requests from before the
 *               rules were data)
 */
public record ApprovalStep(String role, String approverId, String approverName, String approverTitle, String reason,
                           String stage, String status, Instant decidedAt, String note, java.util.List<String> rules) {

    /** A step the system adds itself when the budget ran out while the chain was running. */
    public static final String BUDGET_RACE_RULE = "SYSTEM-BUDGET-RACE";

    public static ApprovalStep pending(String role, String approverId, String approverName, String approverTitle,
                                       String reason, String stage) {
        return pending(role, approverId, approverName, approverTitle, reason, stage, null);
    }

    public static ApprovalStep pending(String role, String approverId, String approverName, String approverTitle,
                                       String reason, String stage, java.util.List<String> rules) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage, "PENDING", null, null,
                rules);
    }

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    /** The approver returned the award to the buyer instead of approving or rejecting it. */
    public ApprovalStep sendBack(Instant at, String note) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage, "SENT_BACK", at, note,
                rules);
    }

    /** A decision that no longer counts but stays in the record (a lapsed approval, a send-back). */
    public boolean isHistory() {
        return "SUPERSEDED".equals(status) || "SENT_BACK".equals(status);
    }

    /** An approval of a price that no longer stands (the order was cancelled and the request sourced again). */
    public ApprovalStep supersede(Instant at, String why) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage, "SUPERSEDED", at,
                why, rules);
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }

    /** The approver sent it to strategic review instead of deciding (policy 6). */
    public ApprovalStep escalate(String note, Instant at) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage, "ESCALATED", at, note, rules);
    }

    public ApprovalStep decide(boolean approved, String note, Instant at) {
        return new ApprovalStep(role, approverId, approverName, approverTitle, reason, stage,
                approved ? "APPROVED" : "REJECTED", at, note, rules);
    }
}
