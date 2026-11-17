package com.compras.procurement.intake;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One conversation, several requests; a question never wipes an unconfirmed card. */
class ConversationTest {

    private static Proposal card(String title) {
        return new Proposal(Proposal.Type.STANDARD, title, null, null, "LAPTOP", "Laptops", java.util.Map.of(),
                List.of(), List.of(), List.of(), null, "EUR", List.of(), List.of(), null, null, List.of(), List.of(),
                "WITHIN_BUDGET", List.of());
    }

    @Test
    void askingWhileACardIsOpenKeepsTheCard() {
        Conversation c = new Conversation("E001", Instant.now());
        c.waitForConfirmation("call-1", card("25 x Designer kit"));
        assertTrue(c.proposalCallPending());
        c.resolvePending();                       // the employee typed a question instead
        assertNotNull(c.getProposal());
        c.waitForAnswers("call-2", List.of(new AgentQuestion("q1", "Which brand?", List.of(), "Other"))); // the agent asks back
        assertNotNull(c.getProposal());
        assertTrue(!c.proposalCallPending());
    }

    @Test
    void severalSubmissionsAreKept() {
        Conversation c = new Conversation("E001", Instant.now());
        c.addSubmitted(new Conversation.Submitted("r1", "PR-1", "Laptops", "STANDARD", Instant.now()));
        c.addSubmitted(new Conversation.Submitted("r2", "PR-2", "Desks", "SPECIAL", Instant.now()));
        assertEquals(2, c.getSubmitted().size());
        assertEquals("r2", c.getRequestId());
        assertTrue(AgentIntakeService.state(c).contains("PR-1: Laptops"));
    }
}
