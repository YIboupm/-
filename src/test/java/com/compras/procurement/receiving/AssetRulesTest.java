package com.compras.procurement.receiving;

import com.compras.procurement.policy.AssetPolicy;
import com.compras.procurement.policy.PolicyFiles;
import com.compras.procurement.policy.PolicySet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Policy 13: what is registered and who receives it, as the shipped rules say. */
class AssetRulesTest {

    private final PolicySet policy = PolicyFiles.builtin();

    private AssetPolicy.Decision decide(AssetClass cls, String price) {
        return AssetPolicy.decide(policy, AssetPolicy.facts(cls.name(), cls.itDevice(), new BigDecimal(price), null));
    }

    @Test
    void itEquipmentIsAlwaysRegisteredAndReceivedByIt() {
        for (AssetClass c : List.of(AssetClass.LAPTOP, AssetClass.PERIPHERAL, AssetClass.MONITOR)) {
            AssetPolicy.Decision d = decide(c, "39");
            assertTrue(d.track());
            assertEquals("IT_ASSET_MANAGER", d.receiver());
        }
    }

    @Test
    void otherGoodsAreRegisteredOnlyAboveTheThreshold() {
        assertTrue(decide(AssetClass.FURNITURE, "100.01").track());
        assertTrue(!decide(AssetClass.FURNITURE, "100").track());
        assertEquals("FACILITIES", decide(AssetClass.FURNITURE, "290").receiver());
        assertTrue(decide(AssetClass.FURNITURE, "190").track());
        assertEquals("Unit price €80 is not over €100 and it is not IT equipment: received, not registered",
                decide(AssetClass.FURNITURE, "80").reason());
    }

    @Test
    void consumablesAreNeverRegistered() {
        assertTrue(!decide(AssetClass.CONSUMABLE, "900").track());
    }

    @Test
    void helpers() {
        assertEquals("MAD", ReceivingService.siteCode("Madrid HQ"));
        assertEquals("GEN", ReceivingService.siteCode("—"));
        assertEquals(List.of("A1", "B2"), ReceivingService.clean(java.util.Arrays.asList(" A1 ", "", null, "B2")));
        assertEquals(AssetClass.FURNITURE, AssetClass.parse(" furniture ").orElseThrow());
        assertTrue(AssetClass.parse("SPACESHIP").isEmpty());
    }
}
