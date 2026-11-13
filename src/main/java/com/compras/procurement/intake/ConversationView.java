package com.compras.procurement.intake;

import com.compras.procurement.matching.RequirementCheck;
import com.compras.procurement.resolution.ResolvedLine;
import com.compras.procurement.rules.RuleViolation;

import java.math.BigDecimal;
import java.util.List;

/** What the browser receives after every step: the dialogue plus the live structured draft. */
public record ConversationView(
        String id,
        String employeeId,
        String status,
        String categoryCode,
        String categoryName,
        List<Conversation.Turn> turns,
        List<FieldView> fields,
        List<ResolvedLine> lines,
        BigDecimal estimatedTotal,
        String currency,
        List<RuleViolation> issues,
        List<String> missingFields,
        List<String> quickReplies,
        List<Question> questions,
        String rationale,
        List<RequirementCheck> checks,
        List<String> specialRequirements,
        String chargeToCostCenter,
        String handoffReason,
        String requestId,
        String requestNumber,
        String requestStatus
) {
    public record FieldView(String name, String label, String type, boolean required, Object value, String source) {
    }
}
