package com.compras.procurement.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("procurement.approval")
public record ApprovalProperties(boolean autoApprove) {
}
