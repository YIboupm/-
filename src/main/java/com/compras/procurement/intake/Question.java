package com.compras.procurement.intake;

import java.util.List;

/**
 * A multiple-choice question for one missing field. The employee answers by picking an option
 * (its value is canonical: a SKU, an ISO date, a number ...) or by typing "Other". The model may
 * write the question and suggest options, but it never fills the field itself: only the
 * employee's answer does.
 *
 * @param field      template field name, or {@link #DECISION} for "standard or special request?"
 * @param allowOther whether the UI offers a free-text "Other" answer
 */
public record Question(String field, String text, List<Option> options, boolean allowOther) {

    public static final String DECISION = "_decision";

    public record Option(String label, Object value) {
    }
}
