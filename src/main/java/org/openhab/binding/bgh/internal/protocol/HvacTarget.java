package org.openhab.binding.bgh.internal.protocol;

import static org.openhab.binding.bgh.internal.protocol.BghProtocol.KNOWN_FLAGS;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Estado deseado que se envía con {@code ThermostatSetMode} (0x61).
 * Payload: {@code [modo, ventilador, flags, tempLo, tempHi]} con {@code temp = round(|°C| * 100)} uint16 LE.
 */
@NonNullByDefault
public record HvacTarget(int mode, int fan, int flags, double setpointCelsius) {

    public HvacTarget withMode(int newMode) {
        return new HvacTarget(newMode, fan, flags, setpointCelsius);
    }

    public HvacTarget withFan(int newFan) {
        return new HvacTarget(mode, newFan, flags, setpointCelsius);
    }

    public HvacTarget withSetpoint(double newSetpointCelsius) {
        return new HvacTarget(mode, fan, flags, newSetpointCelsius);
    }

    public byte[] toPayload() {
        long centi = Math.round(Math.abs(setpointCelsius) * 100);
        return new byte[] { (byte) mode, (byte) fan, (byte) (flags & KNOWN_FLAGS), (byte) (centi & 0xFF),
                (byte) ((centi >> 8) & 0xFF) };
    }
}
