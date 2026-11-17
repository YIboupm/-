package com.compras.procurement.intake.agent;

import com.compras.procurement.intake.Conversation;
import com.compras.procurement.policy.RouteDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A strategic route is never proposed before the employee has heard the reasons and answered. */
class StrategicGateTest {

    private final ObjectMapper json = new ObjectMapper();
    private final RouteDecision strategic = new RouteDecision("STRATEGIC",
            List.of("Sole source: only one supplier can provide it (policy 7, 10)"), true, List.of("R-SOLE-SOURCE"));

    @Test
    void notStrategicPassesStraightThrough() throws Exception {
        ToolContext ctx = new ToolContext(new Conversation("E001", Instant.now()), null, LocalDate.now());
        assertNull(StrategicGate.check(new RouteDecision("BUYER_SOURCED", List.of(), false, List.of()), json.readTree("{}"),
                ctx, "propose_special_request"));
    }

    @Test
    void firstCallExplainsInsteadOfProposing() throws Exception {
        ToolContext ctx = new ToolContext(new Conversation("E001", Instant.now()), null, LocalDate.now());
        ToolResult r = StrategicGate.check(strategic, json.readTree("{\"strategic_confirmed\":true}"), ctx, "t");
        assertNotNull(r); // even a premature "confirmed" only gets the explanation the first time
        assertNull(r.proposal());
    }

    @Test
    void confirmingWithoutTheEmployeeAnsweringIsRefused() throws Exception {
        Conversation c = new Conversation("E001", Instant.now());
        c.say("user", "I need desks", Instant.now());
        ToolContext ctx = new ToolContext(c, null, LocalDate.now());
        assertNotNull(StrategicGate.check(strategic, json.readTree("{}"), ctx, "t"));
        boolean refused = false;
        try {
            StrategicGate.check(strategic, json.readTree("{\"strategic_confirmed\":true}"), ctx, "t");
        } catch (ToolException e) {
            refused = true;
        }
        assertTrue(refused);
    }

    @Test
    void confirmingAfterTheEmployeeAnsweredGoesAhead() throws Exception {
        Conversation c = new Conversation("E001", Instant.now());
        ToolContext ctx = new ToolContext(c, null, LocalDate.now());
        assertNotNull(StrategicGate.check(strategic, json.readTree("{}"), ctx, "t"));
        c.say("user", "Yes, only they make the docking compatible with our desks", Instant.now());
        assertNull(StrategicGate.check(strategic, json.readTree("{\"strategic_confirmed\":true}"), ctx, "t"));
    }

    @Test
    void newReasonsNeedANewConversation() throws Exception {
        Conversation c = new Conversation("E001", Instant.now());
        ToolContext ctx = new ToolContext(c, null, LocalDate.now());
        StrategicGate.check(strategic, json.readTree("{}"), ctx, "t");
        c.say("user", "ok", Instant.now());
        RouteDecision other = new RouteDecision("STRATEGIC", List.of("Contract value €68,150 is over €25,000 (policy 7)"), true, List.of("R-VALUE"));
        assertNotNull(StrategicGate.check(other, json.readTree("{\"strategic_confirmed\":true}"), ctx, "t"));
        assertEquals(1, (int) c.userTurns());
    }
}
