package com.compras.procurement.intake;

import com.compras.procurement.matching.Requirement;

import java.util.List;
import java.util.Map;

/** JSON contracts the model must answer with. Parsed by {@link com.compras.procurement.llm.ModelGateway}. */
public final class ModelOutputs {

    private ModelOutputs() {
    }

    public enum Intent { PURCHASE_REQUEST, STATUS_QUERY, POLICY_QUESTION, OTHER }

    /** Step 1: what is this message, and which category template applies? */
    public record Classification(Intent intent, String categoryCode, Double confidence, String reply) {
    }

    /** One proposed field value with its provenance as the model sees it. */
    public record ProposedValue(Object value, String source) {
        public Conversation.Source toSource() {
            return "stated".equalsIgnoreCase(source) ? Conversation.Source.STATED : Conversation.Source.INFERRED;
        }
    }

    /**
     * Step 2: fill the template's fields from the dialogue and ask about what is missing.
     *
     * @param reply answer to a question the employee asked (specs, differences, price), from catalog data only
     * @param rationale one sentence for the employee: why the selected catalog option fits their need
     * @param requirements every condition the employee put on the item, parsed into attribute / operator /
     *                     value; whether the catalog meets them is decided by code, not by the model
     * @param escalateToBuyer the employee explicitly chose a special (non-standard) request
     */
    public record Extraction(Map<String, ProposedValue> fields, List<ModelQuestion> questions,
                             String reply, String rationale, List<Requirement> requirements,
                             Boolean escalateToBuyer) {
    }

    /** A question the model proposes for one missing field; options carry canonical values. */
    public record ModelQuestion(String field, String question, List<Question.Option> options) {
    }
}
