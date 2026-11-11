package org.openhab.binding.bgh.internal.protocol;

import java.util.Arrays;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Modos del termostato ({@code EndpointControlThermostatView}).
 */
@NonNullByDefault
public enum HvacMode {
    OFF(0, "off"),
    COOL(1, "cool"),
    HEAT(2, "heat"),
    DRY(3, "dry"),
    FAN(4, "fan"),
    AUTO(0xFE, "auto");

    private final int code;
    private final String id;

    HvacMode(int code, String id) {
        this.code = code;
        this.id = id;
    }

    public int code() {
        return code;
    }

    public String id() {
        return id;
    }

    public static Optional<HvacMode> fromCode(int code) {
        return Arrays.stream(values()).filter(mode -> mode.code == code).findFirst();
    }

    public static Optional<HvacMode> fromId(String id) {
        return Arrays.stream(values()).filter(mode -> mode.id.equalsIgnoreCase(id.trim())).findFirst();
    }

    /** Nombre del modo, o {@code unknown_<código>} si el equipo informa uno no documentado. */
    public static String describe(int code) {
        return fromCode(code).map(HvacMode::id).orElse("unknown_" + code);
    }
}
