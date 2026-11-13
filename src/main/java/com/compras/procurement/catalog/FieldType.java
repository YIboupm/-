package com.compras.procurement.catalog;

/** Value types a category template field can have. Each one has its own normalisation rule. */
public enum FieldType {
    TEXT,
    INTEGER,
    DATE,
    BOOLEAN,
    ENUM,
    /** A SKU from this category's catalog (e.g. the laptop tier). */
    SKU,
    /** An asset tag the employee can see (e.g. the printer that needs toner). */
    ASSET,
    /** A standard role kit code (e.g. the onboarding bundle for developers). */
    KIT,
    /** A department from the finance master data; decides the cost centre that pays. */
    DEPARTMENT
}
