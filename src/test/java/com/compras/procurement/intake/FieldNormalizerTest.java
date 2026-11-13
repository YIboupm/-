package com.compras.procurement.intake;

import com.compras.procurement.catalog.FieldSpec;
import com.compras.procurement.catalog.FieldType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guard rail between model output and the database. */
class FieldNormalizerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private final FieldNormalizer normalizer = new FieldNormalizer(null, null); // catalog not needed for these types

    private final FieldSpec quantity = new FieldSpec("quantity", "Quantity", FieldType.INTEGER, true, "", null, 1, 50, null);
    private final FieldSpec neededBy = new FieldSpec("neededBy", "Needed by", FieldType.DATE, true, "", null, null, null, null);

    @Test
    void acceptsNumbersInAnyShapeTheModelSends() {
        assertEquals(3, normalizer.normalize(quantity, 3, null, null, TODAY).value());
        assertEquals(3, normalizer.normalize(quantity, "3", null, null, TODAY).value());
        assertEquals(3, normalizer.normalize(quantity, 3.0, null, null, TODAY).value());
    }

    @Test
    void rejectsQuantitiesOutsideTheTemplateRange() {
        assertFalse(normalizer.normalize(quantity, 500, null, null, TODAY).isOk());
        assertFalse(normalizer.normalize(quantity, 0, null, null, TODAY).isOk());
        assertFalse(normalizer.normalize(quantity, 2.5, null, null, TODAY).isOk());
        assertFalse(normalizer.normalize(quantity, "a few", null, null, TODAY).isOk());
    }

    @Test
    void acceptsFutureIsoDatesAndRejectsPastOnes() {
        assertEquals("2026-12-01", normalizer.normalize(neededBy, "2026-12-01", null, null, TODAY).value());
        assertFalse(normalizer.normalize(neededBy, "2026-11-01", null, null, TODAY).isOk());
        assertFalse(normalizer.normalize(neededBy, "next week", null, null, TODAY).isOk());
    }

    @Test
    void treatsNullAndBlankAsNotProvided() {
        FieldNormalizer.Result r = normalizer.normalize(quantity, "null", null, null, TODAY);
        assertFalse(r.isOk());
        assertTrue(r.problem() == null, "an absent value is not an error, just missing");
    }
}
