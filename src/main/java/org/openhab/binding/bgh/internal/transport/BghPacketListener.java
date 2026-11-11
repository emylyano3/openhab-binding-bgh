package org.openhab.binding.bgh.internal.transport;

import java.net.InetAddress;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.bgh.internal.protocol.BghPacket;

/**
 * Recibe los paquetes que llegan al puerto local del binding.
 */
@NonNullByDefault
public interface BghPacketListener {

    void onPacket(BghPacket packet, InetAddress sender);
}
