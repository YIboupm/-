package com.compras.procurement.request;

/**
 * One step of a request's route, copied from the policy version when the request was routed.
 *
 * @param type   the building block: APPROVAL, SOURCING, STRATEGIC_REVIEW, BUDGET_RESERVE or FULFIL
 * @param stage  for APPROVAL: which approval rules apply (NEED, SPEND...)
 * @param owner  for SOURCING: BUYER or CATEGORY_MANAGER
 * @param status PENDING, ACTIVE (waiting for someone), DONE, SKIPPED, or REJECTED / CANCELLED where it stopped
 */
public record FlowStep(String type, String stage, String owner, String label, String status) {

    public static final String PENDING = "PENDING";
    public static final String ACTIVE = "ACTIVE";
    public static final String DONE = "DONE";
    public static final String SKIPPED = "SKIPPED";
    /** The request was rejected, or cancelled, while at this step. */
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    public FlowStep with(String newStatus) {
        return new FlowStep(type, stage, owner, label, newStatus);
    }

    public boolean open() {
        return PENDING.equals(status) || ACTIVE.equals(status);
    }
}
