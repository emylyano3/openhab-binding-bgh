package org.openhab.binding.bgh.internal.protocol;

import static org.openhab.binding.bgh.internal.protocol.BghProtocol.KNOWN_FLAGS;
import static org.openhab.binding.bgh.internal.protocol.BghProtocol.STATUS_TYPE_HVAC;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Estado HVAC de un {@code StatusReport} (0xFD): {@code [StatusType=6]} + 11 bytes.
 *
 * <pre>
 * 1      modo
 * 2      ventilador
 * 3      flags
 * 4-5    temperatura medida   int16 LE / 100
 * 6-7    temperatura objetivo int16 LE / 100
 * 8-9    timer on (min)       uint16 LE
 * 10-11  timer off (min)      uint16 LE
 * </pre>
 */
@NonNullByDefault
public record HvacStatus(int mode, int fan, int flags, double measuredCelsius, double desiredCelsius,
        int timerOnMinutes, int timerOffMinutes) {

    private static final int LENGTH = 12;

    public static Optional<HvacStatus> parse(byte[] payload) {
        return Optional.of(payload)
                .filter(bytes -> bytes.length >= LENGTH && (bytes[0] & 0xFF) == STATUS_TYPE_HVAC)
                .map(HvacStatus::decode);
    }

    private static HvacStatus decode(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        return new HvacStatus(payload[1] & 0xFF, payload[2] & 0xFF, payload[3] & 0xFF, buffer.getShort(4) / 100.0,
                buffer.getShort(6) / 100.0, buffer.getShort(8) & 0xFFFF, buffer.getShort(10) & 0xFFFF);
    }

    public boolean isOn() {
        return mode != HvacMode.OFF.code();
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    public HvacTarget toTarget() {
        return new HvacTarget(mode, fan, flags & KNOWN_FLAGS, desiredCelsius);
    }
}
