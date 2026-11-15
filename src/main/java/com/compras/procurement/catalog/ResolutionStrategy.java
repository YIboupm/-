package com.compras.procurement.catalog;

/** How the filled-in fields of a category become concrete catalog lines. */
public enum ResolutionStrategy {
    /** The employee's need is mapped straight onto one SKU (laptop tier, monitor ...). */
    DIRECT_SKU,
    /** The SKU follows from an existing asset (toner that fits a given printer). */
    ASSET_COMPATIBILITY,
    /** A role is expanded into a standard bundle of SKUs (onboarding kit). */
    ROLE_KIT,
    /**
     * A call-off under a framework agreement billed by consumption (cloud): the line is the estimated monthly
     * spend times the term in months.
     */
    CONSUMPTION
}
