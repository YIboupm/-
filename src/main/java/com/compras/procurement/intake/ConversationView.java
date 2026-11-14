package com.compras.procurement.intake;

import java.util.List;

/** What the browser receives: the dialogue, the agent's plan, open questions and the proposal to confirm. */
public record ConversationView(
        String id,
        String employeeId,
        String status,
        List<Conversation.Turn> turns,
        List<Conversation.PlanItem> plan,
        String planExplanation,
        List<AgentQuestion> questions,
        Proposal proposal,
        String requestId,
        String requestNumber,
        String requestStatus
) {
}
