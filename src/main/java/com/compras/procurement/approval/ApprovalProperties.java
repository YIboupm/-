package com.compras.procurement.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * The approval matrix (amount bands + category rules). Values are company policy, so they live in configuration.
 *
 * @param autoApproveUpTo catalog items up to this total, within budget, are approved without a person
 * @param managerUpTo     up to this total the line manager (budget holder) alone approves
 * @param cfoAbove        above this total the CFO also approves
 * @param itCategories    categories IT must also approve (compatibility, security, asset register)
 * @param cloudCategories call-offs under a cloud enterprise agreement: the cloud platform team approves, and
 *                        data protection when the data is sensitive or leaves the EU
 */
@ConfigurationProperties("procurement.approval")
public record ApprovalProperties(BigDecimal autoApproveUpTo, BigDecimal managerUpTo, BigDecimal cfoAbove,
                                 List<String> itCategories, List<String> cloudCategories) {

    public ApprovalProperties {
        autoApproveUpTo = autoApproveUpTo == null ? new BigDecimal("1000") : autoApproveUpTo;
        managerUpTo = managerUpTo == null ? new BigDecimal("5000") : managerUpTo;
        cfoAbove = cfoAbove == null ? new BigDecimal("25000") : cfoAbove;
        itCategories = itCategories == null ? List.of("LAPTOP", "ONBOARDING_KIT") : itCategories;
        cloudCategories = cloudCategories == null ? List.of("CLOUD") : cloudCategories;
    }
}
