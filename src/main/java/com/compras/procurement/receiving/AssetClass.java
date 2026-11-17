package com.compras.procurement.receiving;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * What a delivered item is, for the asset register. A vocabulary in code, like the policy's facts: the asset rules
 * (data) decide what is registered and who receives it; this says which classes exist and which are IT equipment.
 */
public enum AssetClass {
    LAPTOP("LAP", true, "Laptop"),
    DESKTOP("DSK", true, "Desktop computer"),
    MONITOR("MON", true, "Monitor or display"),
    DOCK("DCK", true, "Docking station"),
    PERIPHERAL("PER", true, "Keyboard, mouse, webcam or similar"),
    HEADSET("HDS", true, "Headset"),
    PHONE("PHN", true, "Mobile phone"),
    TABLET("TAB", true, "Tablet"),
    PRINTER("PRN", true, "Printer or scanner"),
    SERVER("SRV", true, "Server or workstation for shared use"),
    NETWORK("NET", true, "Network equipment"),
    FURNITURE("FUR", false, "Desk, chair, cabinet or other furniture"),
    APPLIANCE("APL", false, "Appliance (coffee machine, fridge, projector screen...)"),
    CONSUMABLE("CON", false, "Consumable (toner, paper, cables...)"),
    OTHER("OTH", false, "Anything else");

    private final String prefix;
    private final boolean itDevice;
    private final String description;

    AssetClass(String prefix, boolean itDevice, String description) {
        this.prefix = prefix;
        this.itDevice = itDevice;
        this.description = description;
    }

    public String prefix() {
        return prefix;
    }

    public boolean itDevice() {
        return itDevice;
    }

    public String description() {
        return description;
    }

    public static Optional<AssetClass> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String v = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.name().equals(v)).findFirst();
    }
}
