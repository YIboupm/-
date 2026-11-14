package com.compras.procurement.intake;

import java.util.List;

/**
 * A question the agent asks with the ask_user tool. The interface adds a free-text "Other" answer.
 *
 * @param id         stable id the answer is mapped back to
 * @param otherLabel label of the free-text answer, in the employee's language and fitting the question
 *                   (e.g. "其他理由", "Another date")
 */
public record AgentQuestion(String id, String question, List<Option> options, String otherLabel) {

    /** @param value optional machine value (a date, a SKU ...); the label is what the employee sees */
    public record Option(String label, String value, String description) {
    }
}
