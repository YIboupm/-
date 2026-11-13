package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.EmployeeContext;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Picks the resolver that matches a category's strategy. Spring injects every resolver bean. */
@Component
public class LineResolverRegistry {

    private final Map<ResolutionStrategy, LineResolver> resolvers = new EnumMap<>(ResolutionStrategy.class);

    public LineResolverRegistry(List<LineResolver> all) {
        all.forEach(r -> resolvers.put(r.strategy(), r));
    }

    public List<ResolvedLine> resolve(CategoryDefinition category, Map<String, Object> fields, EmployeeContext context) {
        LineResolver resolver = resolvers.get(category.resolution());
        if (resolver == null) {
            throw new IllegalStateException("No resolver for strategy " + category.resolution());
        }
        return resolver.resolve(category, fields, context);
    }
}
