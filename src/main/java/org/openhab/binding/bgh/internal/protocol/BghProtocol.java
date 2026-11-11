package org.openhab.binding.bgh.internal.protocol;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Constantes del protocolo LAN de Solidmation (ver PROTOCOL.md en la raíz del repositorio).
 */
@NonNullByDefault
public final class BghProtocol {

    public static final int PORT_DEVICE = 20910;
    public static final int PORT_LOCAL = 20911;
    public static final String BROADCAST_ADDRESS = "255.255.255.255";
    public static final int HEADER_LENGTH = 17;

    public static final int CMD_HVAC_SET_MODE = 0x61;
    public static final int CMD_ACK = 0x80;
    public static final int CMD_NAK = 0x81;
    public static final int CMD_JOIN = 0xA1;
    public static final int CMD_POLL = 0xE4;
    public static final int CMD_DEBUG = 0xF5;
    public static final int CMD_STATUS = 0xFD;

    public static final int JOIN_ENUMERATE_RESPONSE = 0x02;
    public static final int JOIN_ENUMERATE_ALL = 0x04;
    public static final int JOIN_SUBSCRIBE = 0x0C;

    public static final int STATUS_TYPE_HVAC = 6;

    public static final int FLAG_TURBO = 0x08;
    public static final int FLAG_SWING_H = 0x10;
    public static final int FLAG_SWING_V = 0x20;
    public static final int KNOWN_FLAGS = FLAG_TURBO | FLAG_SWING_H | FLAG_SWING_V;

    private BghProtocol() {
    }
}
