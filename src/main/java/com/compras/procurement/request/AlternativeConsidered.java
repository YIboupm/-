package com.compras.procurement.request;

/**
 * Something that could have covered the need but was not used: an idle or shared company resource, or a
 * catalog item. Recorded so the buyer sees what was ruled out and why.
 *
 * @param id     resource id or catalog SKU
 * @param name   human-readable name
 * @param kind   "Company resource" or "Catalog item"
 * @param whyNot the reason, in the employee's terms
 */
public record AlternativeConsidered(String id, String name, String kind, String whyNot) {
}
