package com.compras.procurement.consolidation;

/** Why a group of pooled demand was turned into a purchase order. */
public enum ReleaseReason {
    /** The request was marked urgent and bypassed the pool. */
    URGENT,
    /** The weekly consolidation window was reached. */
    CONSOLIDATION_WINDOW,
    /** Enough quantity accumulated to reach the supplier's best price tier. */
    BEST_PRICE_REACHED,
    /** Waiting longer would miss the earliest needed-by date (lead time + buffer). */
    DEADLINE,
    /** A buyer sourced a non-standard item; it is ordered on its own, outside the pool. */
    SPECIAL_REQUEST,
    /** A call-off under a framework agreement (cloud): provisioned per request, never pooled. */
    FRAMEWORK_CALL_OFF
}
