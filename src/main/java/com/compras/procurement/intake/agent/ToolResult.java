package com.compras.procurement.intake.agent;

import com.compras.procurement.intake.AgentQuestion;
import com.compras.procurement.intake.Proposal;

import java.util.List;

/**
 * Output of a tool. {@code payload} is serialised to JSON for the model. A tool that needs the
 * employee (questions or a proposal to confirm) returns a pause: the loop stops and the tool call is
 * answered later with the employee's response.
 *
 * @param trace one short line shown in the chat so the employee can see what the agent did
 */
public record ToolResult(Object payload, String trace, List<AgentQuestion> questions, Proposal proposal) {

    public static ToolResult of(Object payload, String trace) {
        return new ToolResult(payload, trace, null, null);
    }

    public static ToolResult ask(List<AgentQuestion> questions, String trace) {
        return new ToolResult(null, trace, questions, null);
    }

    public static ToolResult propose(Proposal proposal, String trace) {
        return new ToolResult(null, trace, null, proposal);
    }

    public boolean pauses() {
        return questions != null || proposal != null;
    }
}
