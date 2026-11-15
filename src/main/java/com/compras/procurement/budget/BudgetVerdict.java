package com.compras.procurement.budget;

/**
 * The only budget information that leaves the finance boundary towards requesters and the AI agent:
 * a yes/no answer, never an amount.
 */
public enum BudgetVerdict {
    /** The department's remaining budget for this quarter covers the amount. */
    WITHIN_BUDGET,
    /** It does not: the request can still be submitted, and the Finance Controller decides. */
    OVER_BUDGET,
    /** Not answered (e.g. too many checks in one conversation, or no amount yet). */
    NOT_DISCLOSED
}
