package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.EmployeeContext;

import java.util.List;
import java.util.Map;

/**
 * Turns the normalised fields of a request into catalog lines. One implementation per
 * {@link ResolutionStrategy}; a new kind of category (e.g. consumption-based supplies) is added
 * by writing one more resolver, without touching the intake flow.
 *
 * Resolution is deterministic: the model only fills fields, the SKUs come from rules and data.
 */
public interface LineResolver {

    ResolutionStrategy strategy();

    /** Returns an empty list when the fields needed for resolution are not complete yet. */
    List<ResolvedLine> resolve(CategoryDefinition category, Map<String, Object> fields, EmployeeContext context);
}
