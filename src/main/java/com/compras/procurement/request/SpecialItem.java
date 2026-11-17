package com.compras.procurement.request;

import com.compras.procurement.matching.Requirement;

import java.math.BigDecimal;
import java.util.List;

/**
 * One line of a special request: one kind of thing (a desk, a chair), with its own quantity and must-haves.
 * A request is the need (one approval, thresholds on the total); its lines are what gets quoted, awarded,
 * ordered and received, possibly from different suppliers.
 *
 * @param referenceUnitPrice a price per unit the assistant found on a page it cited, used to catch an award that
 *                           cannot be right (e.g. a chair's price for a desk and a chair); null when none was found
 * @param preferredModel     the product the employee chose for this line, if any: buying something else needs a reason
 */
public record SpecialItem(String id, String name, int quantity, List<Requirement> requirements,
                          BigDecimal referenceUnitPrice, String referenceSource, String preferredModel) {

    public List<Requirement> requirements() {
        return requirements == null ? List.of() : requirements;
    }
}
