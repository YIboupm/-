package com.compras.procurement.fulfillment;

import com.compras.procurement.context.Asset;
import com.compras.procurement.context.AssetRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Company resources that could be reused: idle or shared assets from the register, plus the live Mac. */
@Service
public class CompanyResourceService {

    private final AssetRepository assets;
    private final MacResourceMonitor mac;

    public CompanyResourceService(AssetRepository assets, MacResourceMonitor mac) {
        this.assets = assets;
        this.mac = mac;
    }

    /**
     * Reusable resources. Computing resources (laptops, servers, shared compute) are always returned together,
     * because a shared server can replace a device; printers only when asked for.
     */
    public List<CompanyResource> available(String type) {
        boolean printers = type != null && type.equalsIgnoreCase("PRINTER");
        List<CompanyResource> result = new ArrayList<>();
        for (Asset a : assets.findAll()) {
            boolean reusable = "IDLE".equals(a.status()) || "SHARED".equals(a.status());
            boolean isPrinter = "PRINTER".equalsIgnoreCase(a.type());
            if (reusable && isPrinter == printers) {
                result.add(fromAsset(a));
            }
        }
        if (!printers && mac.isAvailable()) {
            result.add(fromSnapshot(mac.snapshot()));
        }
        return result;
    }

    /** Fresh data for one resource (the live Mac is measured again). */
    public Optional<CompanyResource> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        if (mac.isAvailable() && id.equalsIgnoreCase("MAC-SERVER-01")) {
            return Optional.of(fromSnapshot(mac.snapshot()));
        }
        return assets.findById(id.trim()).filter(a -> !"IN_USE".equals(a.status())).map(this::fromAsset);
    }

    private CompanyResource fromAsset(Asset a) {
        String access = "PRINTER".equals(a.type()) ? "Shared on site" : "Reassign the device to the requester";
        return new CompanyResource(a.assetTag(), a.model(), a.type(), a.status(), a.location(), a.custodian(),
                access, a.attributes() == null ? Map.of() : a.attributes(), false, null,
                "From the asset register; the custodian confirms availability.");
    }

    private CompanyResource fromSnapshot(CompanyResourceSnapshot s) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("os", s.operatingSystem());
        attrs.put("platform", s.cpuModel() != null && s.cpuModel().toLowerCase(Locale.ROOT).startsWith("apple")
                ? "Apple silicon" : s.architecture());
        attrs.put("cpu", s.cpuModel());
        attrs.put("cpuCores", s.cpuCores());
        attrs.put("ramGb", s.memoryGb());
        attrs.put("gpu", s.gpuModel());
        if (s.gpuCores() != null) {
            attrs.put("gpuCores", s.gpuCores());
        }
        attrs.put("gpuType", "integrated (Apple GPU)");
        attrs.put("metalSupported", s.metalSupported());
        attrs.put("gpuComputeApi", s.metalSupported() ? "Metal" : "unknown");
        if (s.cpuUtilizationPercent() != null) {
            attrs.put("cpuUtilizationPercent", s.cpuUtilizationPercent());
        }
        if (s.gpuUtilizationPercent() != null) {
            attrs.put("gpuUtilizationPercent", s.gpuUtilizationPercent());
        }
        return new CompanyResource(s.id(), s.name(), s.type(), s.observedStatus(), s.location(), "IT Service Desk",
                s.accessMode(), attrs, true, s.observedAt(), s.capacityNote());
    }
}
