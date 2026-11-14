package com.compras.procurement.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties("intake")
public record IntakeProperties(
        int maxClarificationRounds,
        BigDecimal budgetApprovalThreshold,
        String currency,
        /** Procurement's service level for special requests, in working days. Quoted to employees as-is. */
        int buyerResponseDays,
        /** Upper bound on model steps (model call + tools) for one employee message. */
        int maxAgentSteps
) {
    public int agentSteps() {
        return maxAgentSteps > 0 ? maxAgentSteps : 8;
    }

}
