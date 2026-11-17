package com.compras.procurement.intake.agent;

import com.compras.procurement.policy.RouteDecision;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Before anything the policy routes as strategic is proposed, the employee must have heard why and had the chance
 * to correct the facts behind it. The route itself is computed by {@code PolicyRouter}; this gate only enforces the
 * conversation: the first proposal returns the reasons instead of a card, and the agent may confirm only after the
 * employee has said something since then (checked on the turn count, not on the model's word).
 */
public final class StrategicGate {

    public static final String ARG = "strategic_confirmed";

    private StrategicGate() {
    }

    public static Map<String, Object> schema() {
        return Schemas.anyValue("Set true only after the system said STRATEGIC, you explained each reason to the "
                + "employee and they confirmed the facts behind it.");
    }

    /** Null: the proposal may go ahead. Otherwise the result to return to the agent instead of a card. */
    public static ToolResult check(RouteDecision route, JsonNode args, ToolContext context, String tool) {
        if (route == null || !route.strategic()) {
            return null;
        }
        var conversation = context.conversation();
        String key = String.join(" | ", new TreeSet<>(route.reasons()));
        Integer openedAt = conversation.strategicGate(key);
        boolean confirmed = args.path(ARG).asBoolean(false) || "true".equalsIgnoreCase(args.path(ARG).asText());
        if (openedAt == null || !confirmed) {
            conversation.openStrategicGate(key);
            return explain(route.reasons(), tool);
        }
        if (conversation.userTurns() <= openedAt) {
            throw new ToolException("Not accepted: the employee has not been consulted.", List.of(
                    "The system said this is STRATEGIC, but the employee has not answered since. Explain the "
                            + "reasons and ask them (ask_user) to confirm the facts before setting " + ARG + "=true."));
        }
        return null;
    }

    private static ToolResult explain(List<String> reasons, String tool) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("route", "STRATEGIC");
        m.put("reasons", reasons);
        m.put("next", "Nothing is proposed yet. The procurement policy routes this as a STRATEGIC purchase (policy 7) "
                + "for the reasons above. Strategic means the category manager decides how it is sourced (an existing "
                + "agreement, quotes or a tender, or a new framework agreement); it usually takes longer, and you do "
                + "not research or recommend suppliers for it. Before proposing: (1) explain each reason to the "
                + "employee in their language, in plain words, and what the strategic route means for them; "
                + "(2) for every reason that rests on what they told you - sole source (only one supplier can do it, "
                + "or just a preferred brand?), the term in months, the data classification, a provider outside the "
                + "company agreements - ask them with ask_user to confirm or correct it. Let them decide the facts; "
                + "if a fact changes, call " + tool + " again with the corrected values and the route is "
                + "recomputed. A value threshold is policy and cannot be waived; never suggest splitting the order to "
                + "get under it (policy 2). (3) When they have confirmed, call " + tool + " again with the same "
                + "arguments and " + ARG + "=true.");
        return ToolResult.of(m, tool + " · strategic: to be discussed with the employee first");
    }
}
