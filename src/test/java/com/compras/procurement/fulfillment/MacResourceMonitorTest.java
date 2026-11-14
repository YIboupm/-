package com.compras.procurement.fulfillment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MacResourceMonitorTest {

    @Test
    void readsAppleGpuUtilisationFromIoregPerformanceStatistics() {
        String output = "\"PerformanceStatistics\" = {\"Renderer Utilization %\"=12,"
                + "\"Device Utilization %\"=37,\"Tiler Utilization %\"=4}";
        assertEquals(37.0, MacResourceMonitor.gpuUtilization(output));
    }

    @Test
    void reportsUnavailableWhenTheMetricIsNotExposed() {
        assertNull(MacResourceMonitor.gpuUtilization("no Apple GPU metrics here"));
    }
}
