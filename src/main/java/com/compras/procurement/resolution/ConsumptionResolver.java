package com.compras.procurement.resolution;

import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.ResolutionStrategy;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.shared.Money;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Cloud under the company's enterprise agreement: the provider is a catalog item (the framework), the amount is
 * the employee's estimated monthly spend times the term. The approval matrix sees the whole contract value.
 */
@Component
public class ConsumptionResolver implements LineResolver {

    private final CatalogService catalog;

    public ConsumptionResolver(CatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public ResolutionStrategy strategy() {
        return ResolutionStrategy.CONSUMPTION;
    }

    @Override
    public List<ResolvedLine> resolve(CategoryDefinition category, Map<String, Object> fields, EmployeeContext context) {
        var sku = FieldValues.string(fields, "item");
        var monthly = FieldValues.integer(fields, "monthlyEstimate");
        var term = FieldValues.integer(fields, "termMonths");
        if (sku.isEmpty() || monthly.isEmpty() || term.isEmpty()) {
            return List.of();
        }
        return catalog.findItem(sku.get())
                .filter(item -> item.categoryCode().equals(category.code()))
                .map(item -> {
                    BigDecimal unit = Money.scale(BigDecimal.valueOf(monthly.get()));
                    String region = FieldValues.string(fields, "region").orElse("");
                    return List.of(new ResolvedLine(item.sku(), item.name() + (region.isEmpty() ? "" : " · " + region),
                            item.categoryCode(), term.get(), unit, Money.times(unit, term.get()), item.supplierId(),
                            item.supplierName(), item.leadTimeDays(), item.specs(),
                            "Estimated " + monthly.get() + " EUR/month × " + term.get() + " months, billed monthly by consumption"));
                })
                .orElse(List.of());
    }
}
