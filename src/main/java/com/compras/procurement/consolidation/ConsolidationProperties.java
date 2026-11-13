package com.compras.procurement.consolidation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.DayOfWeek;

@ConfigurationProperties("procurement.consolidation")
public record ConsolidationProperties(DayOfWeek windowDay, int safetyBufferDays, String cron) {
}
