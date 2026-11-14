package com.compras.procurement.request;

import java.util.List;

/**
 * A concrete product the agent researched for a special request, so a non-technical buyer has a starting
 * point. Specs and prices come only from the cited pages, which the agent saw in this conversation.
 *
 * @param model          e.g. "Mac Studio (M4 Max)"
 * @param configuration  the configuration that meets the must-haves, e.g. "M4 Max 14-core, 36 GB, 512 GB"
 * @param fits           how it meets the must-haves
 * @param tradeOffs      what speaks against it
 * @param price          list price as stated on the source, e.g. "2,499 EUR (Apple ES, incl. VAT)"; may be null
 * @param priceSourceUrl the page the price is from
 * @param sources        pages the specs are from
 * @param preferred      true for the option the employee chose
 */
public record SuggestedModel(String model, String configuration, String fits, String tradeOffs, String price,
                             String priceSourceUrl, List<String> sources, boolean preferred) {
}
