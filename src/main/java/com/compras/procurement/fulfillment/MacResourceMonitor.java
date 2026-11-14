package com.compras.procurement.fulfillment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo adapter that treats the Mac running this application as a shared company Mac server.
 * Hardware information is cached; CPU and Apple-GPU utilisation are sampled for every request.
 */
@Service
public class MacResourceMonitor {

    private static final Logger log = LoggerFactory.getLogger(MacResourceMonitor.class);
    private static final Pattern GPU_USAGE = Pattern.compile("\\\"Device Utilization %\\\"=(\\d+(?:\\.\\d+)?)");
    private static final Pattern GPU_MODEL = Pattern.compile("(?m)^\\s*Chipset Model:\\s*(.+)$");
    private static final Pattern GPU_CORES = Pattern.compile("(?m)^\\s*Total Number of Cores:\\s*(\\d+)\\s*$");
    private static final Pattern CHIP = Pattern.compile("(?m)^\\s*Chip:\\s*(.+)$");
    private static final Pattern MODEL_NAME = Pattern.compile("(?m)^\\s*Model Name:\\s*(.+)$");
    private static final Pattern MEMORY = Pattern.compile("(?m)^\\s*Memory:\\s*([0-9.]+)\\s*GB\\s*$");
    private static final double BUSY_THRESHOLD = 85.0;

    private final Clock clock;
    private final Hardware hardware;

    public MacResourceMonitor(Clock clock) {
        this.clock = clock;
        this.hardware = isAvailable() ? inspectHardware()
                : new Hardware("none", "not macOS", "none", "none", null, false, 0.0);
    }

    /** True only when this application really runs on macOS; elsewhere there is no Mac to offer. */
    public boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    public CompanyResourceSnapshot snapshot() {
        if (!isAvailable()) {
            throw new IllegalStateException("No Mac resource: this application is not running on macOS.");
        }
        Double cpu = cpuUtilization();
        Double gpu = gpuUtilization(run("/bin/sh", "-c",
                "/usr/sbin/ioreg -r -d 1 -c AGXAccelerator -l 2>/dev/null | "
                        + "/usr/bin/grep -m1 '\"PerformanceStatistics\"'"));
        boolean busy = (cpu != null && cpu >= BUSY_THRESHOLD) || (gpu != null && gpu >= BUSY_THRESHOLD);
        return new CompanyResourceSnapshot(
                "MAC-SERVER-01",
                "Company shared Mac server (this Mac)",
                "SHARED_COMPUTE",
                "Company network",
                "Remote access",
                busy ? "CURRENTLY_BUSY" : "CURRENTLY_AVAILABLE",
                hardware.operatingSystem(),
                System.getProperty("os.arch", "unknown"),
                hardware.cpuModel(),
                Runtime.getRuntime().availableProcessors(),
                cpu,
                hardware.gpuModel(),
                hardware.gpuCores(),
                hardware.metalSupported(),
                gpu,
                hardware.memoryGb(),
                "Point-in-time load only; this demo has no reservation calendar. The resource is remote, not portable.",
                Instant.now(clock));
    }

    private Hardware inspectHardware() {
        String profile = run("/usr/sbin/system_profiler", "SPHardwareDataType", "SPDisplaysDataType");
        String osVersion = run("/usr/bin/sw_vers", "-productVersion").trim();
        String model = firstGroup(MODEL_NAME, profile, "Mac");
        String chip = firstGroup(CHIP, profile, System.getProperty("os.arch", "unknown"));
        String gpu = firstGroup(GPU_MODEL, profile, chip + " GPU");
        Integer gpuCores = integerGroup(GPU_CORES, profile);
        double memoryGb = doubleGroup(MEMORY, profile, totalMemoryGb());
        boolean metal = profile.contains("Metal: Supported") || chip.startsWith("Apple M");
        String os = "macOS" + (osVersion.isBlank() ? "" : " " + osVersion);
        log.info("Internal demo resource detected: {} / {} / {} / {} GB", model, chip, gpu, memoryGb);
        return new Hardware(model, os, chip, gpu, gpuCores, metal, memoryGb);
    }

    private Double cpuUtilization() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getCpuLoad();
            if (load >= 0) {
                return round(load * 100.0);
            }
        }
        return null;
    }

    static Double gpuUtilization(String ioregOutput) {
        Matcher m = GPU_USAGE.matcher(ioregOutput == null ? "" : ioregOutput);
        return m.find() ? round(Double.parseDouble(m.group(1))) : null;
    }

    private double totalMemoryGb() {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean os) {
            return round(os.getTotalMemorySize() / 1024.0 / 1024.0 / 1024.0);
        }
        return 0.0;
    }

    private String run(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not run {}: {}", command[0], e.getMessage());
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String firstGroup(Pattern pattern, String text, String fallback) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    private static Integer integerGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static double doubleGroup(Pattern pattern, String text, double fallback) {
        Matcher m = pattern.matcher(text == null ? "" : text);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private record Hardware(String modelName, String operatingSystem, String cpuModel, String gpuModel,
                            Integer gpuCores, boolean metalSupported, double memoryGb) {
    }
}
