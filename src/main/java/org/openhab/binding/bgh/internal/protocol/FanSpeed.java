package org.openhab.binding.bgh.internal.protocol;

import java.util.Arrays;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Velocidades del ventilador.
 */
@NonNullByDefault
public enum FanSpeed {
    LOW(1, "low"),
    MID(2, "mid"),
    HIGH(3, "high"),
    AUTO(0xFE, "auto");

    private final int code;
    private final String id;

    FanSpeed(int code, String id) {
        this.code = code;
        this.id = id;
    }

    public int code() {
        return code;
    }

    public String id() {
        return id;
    }

    public static Optional<FanSpeed> fromCode(int code) {
        return Arrays.stream(values()).filter(speed -> speed.code == code).findFirst();
    }

    public static Optional<FanSpeed> fromId(String id) {
        return Arrays.stream(values()).filter(speed -> speed.id.equalsIgnoreCase(id.trim())).findFirst();
    }

    public static String describe(int code) {
        return fromCode(code).map(FanSpeed::id).orElse("unknown_" + code);
    }
}
