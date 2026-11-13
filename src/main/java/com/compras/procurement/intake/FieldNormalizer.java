package com.compras.procurement.intake;

import com.compras.procurement.catalog.CatalogService;
import com.compras.procurement.catalog.CategoryDefinition;
import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.context.EmployeeContext;
import com.compras.procurement.context.EmployeeContextService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Converts a raw value proposed by the model into the field's canonical form, or rejects it.
 * This is the guard rail between "what the model said" and "what goes into the database":
 * a SKU that is not in the catalog, a date in the past or a quantity of 500 never get stored.
 */
@Component
public class FieldNormalizer {

    /**
     * A canonical value, the reason it was rejected, or neither (the model sent nothing for the
     * field, which is not an error: the field is simply still missing).
     */
    public record Result(Object value, String problem) {
        static Result ok(Object value) { return new Result(value, null); }
        static Result fail(String problem) { return new Result(null, problem); }
        public boolean isOk() { return value != null; }
    }

    private final CatalogService catalog;
    private final EmployeeContextService employees;

    public FieldNormalizer(CatalogService catalog, EmployeeContextService employees) {
        this.catalog = catalog;
        this.employees = employees;
    }

    public Result normalize(FieldSpec spec, Object raw, CategoryDefinition category,
                            EmployeeContext context, LocalDate today) {
        if (raw == null || raw.toString().isBlank() || "null".equalsIgnoreCase(raw.toString().trim())) {
            return Result.fail(null);
        }
        String text = raw.toString().trim();
        String label = spec.label() == null ? spec.name() : spec.label();
        return switch (spec.type()) {
            case TEXT -> Result.ok(text);
            case INTEGER -> integer(spec, raw, text, label);
            case DATE -> date(text, label, today);
            case BOOLEAN -> Result.ok(raw instanceof Boolean b ? b
                    : text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes"));
            case ENUM -> spec.allowedValues() == null ? Result.ok(text)
                    : spec.allowedValues().stream().filter(v -> v.equalsIgnoreCase(text)).findFirst()
                    .<Result>map(Result::ok)
                    .orElse(Result.fail(label + ": '" + text + "' is not one of " + spec.allowedValues()));
            case SKU -> catalog.findItem(text.toUpperCase(Locale.ROOT))
                    .filter(i -> i.categoryCode().equals(category.code()))
                    .<Result>map(i -> Result.ok(i.sku()))
                    .orElse(Result.fail(label + ": '" + text + "' is not a catalog option for " + category.name()));
            case ASSET -> context.asset(text)
                    .filter(a -> category.assetType() == null || a.type().equalsIgnoreCase(category.assetType()))
                    .<Result>map(a -> Result.ok(a.assetTag()))
                    .orElse(Result.fail(label + ": '" + text + "' is not a known asset at your site"));
            case KIT -> catalog.findKit(text.toUpperCase(Locale.ROOT))
                    .<Result>map(k -> Result.ok(k.code()))
                    .orElse(Result.fail(label + ": '" + text + "' is not a standard kit"));
            case DEPARTMENT -> employees.department(text)
                    .<Result>map(d -> Result.ok(d.name()))
                    .orElse(Result.fail(label + ": '" + text + "' is not a known department"));
        };
    }

    private Result integer(FieldSpec spec, Object raw, String text, String label) {
        int value;
        try {
            BigDecimal number = raw instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(text);
            if (number.stripTrailingZeros().scale() > 0) {
                return Result.fail(label + " must be a whole number");
            }
            value = number.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return Result.fail(label + ": '" + text + "' is not a number");
        }
        if (spec.min() != null && value < spec.min()) {
            return Result.fail(label + " must be at least " + spec.min());
        }
        if (spec.max() != null && value > spec.max()) {
            return Result.fail(label + " above " + spec.max() + " needs a buyer; please split or contact procurement");
        }
        return Result.ok(value);
    }

    private Result date(String text, String label, LocalDate today) {
        try {
            LocalDate date = LocalDate.parse(text);
            if (date.isBefore(today)) {
                return Result.fail(label + " " + date + " is in the past");
            }
            return Result.ok(date.toString());
        } catch (DateTimeParseException e) {
            return Result.fail(label + ": '" + text + "' is not a date (yyyy-MM-dd)");
        }
    }
}
