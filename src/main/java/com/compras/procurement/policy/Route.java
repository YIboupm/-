package com.compras.procurement.policy;

/** Section 4: the three routes a purchase can take. */
public enum Route {
    /** Catalog or framework agreement: approval matrix, no buyer. */
    STANDARD,
    /** Outside the catalog, not strategic: a buyer gets quotes and awards. */
    BUYER_SOURCED,
    /** Strategic: the category manager decides how to source it; the assistant does not recommend suppliers. */
    STRATEGIC
}
