package com.compras.procurement.intake;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties("intake")
public record IntakeProperties(
        int maxClarificationRounds,
        BigDecimal budgetApprovalThreshold,
        String currency,
        /** Procurement's service level for special requests, in working days. Quoted to employees as-is. */
        int buyerResponseDays
) {
}
