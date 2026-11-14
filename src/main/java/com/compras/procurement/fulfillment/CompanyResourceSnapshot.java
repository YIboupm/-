package com.compras.procurement.fulfillment;

import java.time.Instant;

/**
 * A point-in-time, read-only view of an internal company resource that may satisfy a need
 * before procurement buys anything.
 */
public record CompanyResourceSnapshot(
        String id,
        String name,
        String type,
        String location,
        String accessMode,
        String observedStatus,
        String operatingSystem,
        String architecture,
        String cpuModel,
        int cpuCores,
        Double cpuUtilizationPercent,
        String gpuModel,
        Integer gpuCores,
        boolean metalSupported,
        Double gpuUtilizationPercent,
        double memoryGb,
        String capacityNote,
        Instant observedAt
) {
}
