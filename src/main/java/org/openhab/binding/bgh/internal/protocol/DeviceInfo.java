package org.openhab.binding.bgh.internal.protocol;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Identidad anunciada por el equipo en la respuesta a Enumerate_All.
 */
@NonNullByDefault
public record DeviceInfo(String model, String firmware) {

    public static DeviceInfo parse(String text) {
        String[] parts = text.trim().split(",", 2);
        return new DeviceInfo(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "");
    }
}
