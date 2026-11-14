package com.compras.procurement.matching;

import com.compras.procurement.catalog.CatalogItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The "Mac for ML on Metal" request that the model alone mapped inconsistently. */
class RequirementMatcherTest {

    private final RequirementMatcher matcher = new RequirementMatcher();

    private static CatalogItem laptop(String sku, int price, String gpuType, int ramGb) {
        return new CatalogItem(sku, "LAPTOP", sku, "", List.of(), Map.of(),
                Map.of("os", "Windows 11", "gpuType", gpuType, "ramGb", ramGb),
                "SUP", "Supplier", "EUR", new BigDecimal(price), List.of(), 5, List.of());
    }

    private final CatalogItem std = laptop("LAP-STD", 899, "integrated", 16);
    private final CatalogItem dev = laptop("LAP-DEV", 1499, "integrated", 32);
    private final CatalogItem des = laptop("LAP-DES", 2199, "dedicated", 32);

    private final Requirement mac = new Requirement("Must run macOS", "os", "eq", "macOS", "must");
    private final Requirement gpu = new Requirement("Strong dedicated GPU", "gpuType", "eq", "dedicated", "must");
    private final Requirement metal = new Requirement("Supports Apple Metal", null, "eq", "Metal", "must");

    @Test
    void comparesWithCatalogDataAndSaysWhenTheCatalogIsSilent() {
        List<RequirementCheck> checks = matcher.check(List.of(dev), List.of(mac, gpu, metal));
        assertEquals(RequirementCheck.Status.NOT_MET, checks.get(0).status());
        assertEquals("Windows 11", checks.get(0).actual());
        assertEquals(RequirementCheck.Status.NOT_MET, checks.get(1).status());
        assertEquals(RequirementCheck.Status.UNKNOWN, checks.get(2).status());
    }

    @Test
    void picksTheTierThatFailsFewestMustHavesEvenWhenNoneIsPerfect() {
        CatalogItem best = matcher.bestMatch(List.of(std, dev, des), List.of(mac, gpu, metal)).orElseThrow();
        assertEquals("LAP-DES", best.sku());
        assertEquals(1L, matcher.failedMusts(best, List.of(mac, gpu, metal)));
        assertEquals(2L, matcher.failedMusts(dev, List.of(mac, gpu, metal)));
    }

    @Test
    void picksTheCheapestTierWhenSeveralMeetEverything() {
        Requirement ram = new Requirement("At least 32 GB RAM", "ramGb", "min", 32, "must");
        assertEquals("LAP-DEV", matcher.bestMatch(List.of(std, dev, des), List.of(ram)).orElseThrow().sku());
    }

    @Test
    void preferencesNeverBlockTheStandardOption() {
        Requirement preferMac = new Requirement("Preferably macOS", "os", "eq", "macOS", "preference");
        RequirementCheck check = matcher.check(List.of(des), List.of(preferMac)).get(0);
        assertEquals(RequirementCheck.Status.NOT_MET, check.status());
        assertFalse(check.blocksStandard());
        assertTrue(matcher.check(List.of(des), List.of(metal)).get(0).blocksStandard());
    }

    @Test
    void supportsExplicitlyForbiddenValues() {
        CatalogItem cuda = new CatalogItem("CUDA", "LAPTOP", "CUDA", "", List.of(), Map.of(),
                Map.of("gpuComputeApi", "CUDA"), "SUP", "Supplier", "EUR", new BigDecimal(1),
                List.of(), 1, List.of());
        CatalogItem directMl = new CatalogItem("DML", "LAPTOP", "DML", "", List.of(), Map.of(),
                Map.of("gpuComputeApi", "DirectML"), "SUP", "Supplier", "EUR", new BigDecimal(1),
                List.of(), 1, List.of());
        Requirement noCuda = new Requirement("Cannot use CUDA", "gpuComputeApi", "neq", "CUDA", "must");

        assertFalse(RequirementMatcher.satisfies("CUDA", noCuda));
        assertTrue(RequirementMatcher.satisfies("DirectML", noCuda));
        assertEquals(RequirementCheck.Status.NOT_MET, matcher.check(List.of(cuda), List.of(noCuda)).get(0).status());
        assertEquals(RequirementCheck.Status.MET, matcher.check(List.of(directMl), List.of(noCuda)).get(0).status());
    }
}
