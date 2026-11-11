package org.openhab.binding.bgh.internal.protocol;

import static org.openhab.binding.bgh.internal.protocol.BghProtocol.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Paquete del protocolo: cabecera de 17 bytes + payload.
 *
 * <pre>
 * 0      ProtocolVersion (0)
 * 1-6    SourceMAC
 * 7-12   DestinationMAC
 * 13     SequenceNumber
 * 14     SourceEndpoint
 * 15     DestinationEndpoint
 * 16     CommandID
 * </pre>
 */
@NonNullByDefault
public record BghPacket(String sourceMac, int sequence, int sourceEndpoint, int destinationEndpoint, int command,
        byte[] payload) {

    private static final byte[] BROADCAST_MAC = { -1, -1, -1, -1, -1, -1 };
    private static final byte[] EMPTY = new byte[0];

    public static Optional<BghPacket> parse(byte[] data) {
        return Optional.of(data).filter(bytes -> bytes.length >= HEADER_LENGTH).map(BghPacket::decode);
    }

    private static BghPacket decode(byte[] data) {
        return new BghPacket(MacAddresses.format(data, 1), data[13] & 0xFF, data[14] & 0xFF, data[15] & 0xFF,
                data[16] & 0xFF, Arrays.copyOfRange(data, HEADER_LENGTH, data.length));
    }

    public static byte[] enumerateAll() {
        return build(BROADCAST_MAC, 0, CMD_JOIN, new byte[] { JOIN_ENUMERATE_ALL });
    }

    public static byte[] subscribe(byte[] mac) {
        return build(mac, 0, CMD_JOIN, new byte[] { JOIN_SUBSCRIBE });
    }

    public static byte[] poll(byte[] mac, int endpoint) {
        return build(mac, endpoint, CMD_POLL, EMPTY);
    }

    public static byte[] setMode(byte[] mac, int endpoint, HvacTarget target) {
        return build(mac, endpoint, CMD_HVAC_SET_MODE, target.toPayload());
    }

    /** Comando HVAC suelto de 1 byte (por ejemplo {@link BghProtocol#HVAC_COMMAND_TURBO}). */
    public static byte[] hvacCommand(byte[] mac, int endpoint, int code) {
        return build(mac, endpoint, CMD_HVAC_COMMAND, new byte[] { (byte) code });
    }

    static byte[] build(byte[] destinationMac, int endpoint, int command, byte[] payload) {
        return build(destinationMac, endpoint, command, payload, ThreadLocalRandom.current().nextInt(256));
    }

    static byte[] build(byte[] destinationMac, int endpoint, int command, byte[] payload, int sequence) {
        return ByteBuffer.allocate(HEADER_LENGTH + payload.length)
                .put((byte) 0)
                .put(new byte[6])
                .put(destinationMac)
                .put((byte) sequence)
                .put((byte) 0)
                .put((byte) endpoint)
                .put((byte) command)
                .put(payload)
                .array();
    }

    public boolean isEnumerateResponse() {
        return command == CMD_JOIN && payload.length > 0 && (payload[0] & 0xFF) == JOIN_ENUMERATE_RESPONSE;
    }

    /** Modelo y firmware anunciados en la respuesta a Enumerate_All ({@code "BGH-7032,1.0.0.0"}). */
    public Optional<DeviceInfo> deviceInfo() {
        return Optional.of(this)
                .filter(BghPacket::isEnumerateResponse)
                .map(BghPacket::payloadText)
                .map(DeviceInfo::parse);
    }

    /** Texto que acompaña al subcomando (sin el primer byte). */
    public String payloadText() {
        return payload.length <= 1 ? "" : new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
    }
}
