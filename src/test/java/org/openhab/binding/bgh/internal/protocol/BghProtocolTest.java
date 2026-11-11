package org.openhab.binding.bgh.internal.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HexFormat;
import java.util.Optional;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Casos tomados de las capturas verificadas contra un BGH-7032 (ver PROTOCOL.md).
 */
@NonNullByDefault
class BghProtocolTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String MAC = "AC:CF:23:12:34:56";

    @Test
    void enumerateAllIsBroadcastJoinWithSubcommand4() {
        byte[] packet = BghPacket.enumerateAll();

        assertEquals(18, packet.length);
        assertEquals("00" + "000000000000" + "ffffffffffff", HEX.formatHex(packet, 0, 13));
        assertEquals("0000a104", HEX.formatHex(packet, 14, 18));
    }

    @Test
    void pollTargetsDeviceMacAndHvacEndpoint() {
        byte[] packet = BghPacket.build(MacAddresses.toBytes(MAC), 1, BghProtocol.CMD_POLL, new byte[0], 0x42);

        assertEquals("00" + "000000000000" + "accf23123456" + "42" + "00" + "01" + "e4", HEX.formatHex(packet));
    }

    @Test
    void setModeEncodesSetpointAsCentiCelsiusLittleEndian() {
        HvacTarget target = new HvacTarget(HvacMode.COOL.code(), FanSpeed.LOW.code(), 0, 24);

        assertEquals("010100" + "6009", HEX.formatHex(target.toPayload()));
    }

    @Test
    void setModeDropsUnknownFlags() {
        HvacTarget target = new HvacTarget(HvacMode.OFF.code(), FanSpeed.MID.code(), 0xFF, 26);

        assertEquals("000238" + "280a", HEX.formatHex(target.toPayload()));
    }

    @Test
    void parsesEnumerateResponse() {
        byte[] data = HEX.parseHex("00" + "accf23123456" + "ffffffffffff" + "17" + "00" + "00" + "a1" + "02"
                + HEX.formatHex("BGH-7032,1.0.0.0".getBytes()));

        BghPacket packet = BghPacket.parse(data).orElseThrow();

        assertEquals(MAC, packet.sourceMac());
        assertEquals(0x17, packet.sequence());
        assertTrue(packet.isEnumerateResponse());
        assertEquals(Optional.of(new DeviceInfo("BGH-7032", "1.0.0.0")), packet.deviceInfo());
    }

    @Test
    void parsesHvacStatusReport() {
        // Apagado, ventilador medio, medida 21 °C (0x0834), objetivo 26 °C (0x0A28), sin timers.
        byte[] data = HEX.parseHex("00" + "accf23123456" + "000000000000" + "05" + "01" + "00" + "fd"
                + "06" + "00" + "02" + "00" + "3408" + "280a" + "0000" + "0000");

        BghPacket packet = BghPacket.parse(data).orElseThrow();
        HvacStatus status = HvacStatus.parse(packet.payload()).orElseThrow();

        assertEquals(BghProtocol.CMD_STATUS, packet.command());
        assertEquals(1, packet.sourceEndpoint());
        assertFalse(status.isOn());
        assertEquals("off", HvacMode.describe(status.mode()));
        assertEquals("mid", FanSpeed.describe(status.fan()));
        assertEquals(21.0, status.measuredCelsius());
        assertEquals(26.0, status.desiredCelsius());
        assertEquals(new HvacTarget(0, 2, 0, 26.0), status.toTarget());
    }

    @Test
    void parsesNegativeMeasuredTemperature() {
        // -5 °C = -500 = 0xFE0C
        HvacStatus status = HvacStatus.parse(HEX.parseHex("06" + "02" + "fe" + "18" + "0cfe" + "6009" + "0000" + "0000"))
                .orElseThrow();

        assertEquals(-5.0, status.measuredCelsius());
        assertEquals("auto", FanSpeed.describe(status.fan()));
        assertTrue(status.hasFlag(BghProtocol.FLAG_SWING_H));
        assertTrue(status.hasFlag(BghProtocol.FLAG_TURBO));
        assertFalse(status.hasFlag(BghProtocol.FLAG_SWING_V));
    }

    @Test
    void rejectsShortOrNonHvacPayloads() {
        assertTrue(BghPacket.parse(new byte[16]).isEmpty());
        assertTrue(HvacStatus.parse(HEX.parseHex("06000200")).isEmpty());
        assertTrue(HvacStatus.parse(HEX.parseHex("01" + "00".repeat(11))).isEmpty());
    }

    @Test
    void normalizesMacAddresses() {
        assertEquals(Optional.of(MAC), MacAddresses.normalize("ac:cf:23:12:34:56"));
        assertEquals(Optional.of(MAC), MacAddresses.normalize(" AC-CF-23-12-34-56 "));
        assertEquals(Optional.of(MAC), MacAddresses.normalize("accf23123456"));
        assertTrue(MacAddresses.normalize("accf231234").isEmpty());
        assertTrue(MacAddresses.normalize("zz:cf:23:12:34:56").isEmpty());
        assertEquals("accf23123456", MacAddresses.toId(MAC));
    }

    @Test
    void unknownCodesAreReportedAsUnknown() {
        assertEquals("unknown_253", HvacMode.describe(253));
        assertEquals(Optional.of(HvacMode.DRY), HvacMode.fromId("DRY"));
        assertTrue(FanSpeed.fromId("turbo").isEmpty());
    }
}
