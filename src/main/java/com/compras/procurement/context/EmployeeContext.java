package com.compras.procurement.context;

import java.util.List;
import java.util.Optional;

/** Everything the system already knows about the requester, so the assistant never has to ask it. */
public record EmployeeContext(Employee employee, List<Asset> visibleAssets) {

    public Optional<Asset> asset(String assetTag) {
        return visibleAssets.stream().filter(a -> a.assetTag().equalsIgnoreCase(assetTag)).findFirst();
    }

    public List<Asset> assetsOfType(String type) {
        return visibleAssets.stream().filter(a -> a.type().equalsIgnoreCase(type)).toList();
    }
}
