package org.openhab.binding.bgh.internal.transport;

import static org.openhab.binding.bgh.internal.protocol.BghProtocol.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bgh.internal.protocol.BghPacket;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Socket UDP único del binding. Los equipos responden siempre al puerto 20911, así que todos los aires y el
 * descubrimiento comparten este socket: los paquetes se reparten por MAC de origen.
 *
 * <p>
 * El socket se abre a demanda (en el primer envío), de modo que si el puerto está ocupado (por ejemplo por la app
 * oficial o por otro cliente del protocolo) el error llega a quien envía y el Thing lo muestra como OFFLINE.
 */
@Component(service = BghUdpTransport.class)
@NonNullByDefault
public class BghUdpTransport {

    private static final long DEDUP_WINDOW_MS = 2000;
    private static final int BUFFER_SIZE = 2048;

    private final Logger logger = LoggerFactory.getLogger(BghUdpTransport.class);
    private final Map<String, BghPacketListener> deviceListeners = new ConcurrentHashMap<>();
    private final Set<BghPacketListener> globalListeners = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> recentPackets = new ConcurrentHashMap<>();

    private @Nullable DatagramSocket socket;

    @Deactivate
    public synchronized void deactivate() {
        Optional.ofNullable(socket).ifPresent(DatagramSocket::close);
        socket = null;
    }

    /** Registra el listener de un equipo, identificado por su MAC normalizada ({@code AC:CF:...}). */
    public void addDeviceListener(String mac, BghPacketListener listener) {
        deviceListeners.put(mac, listener);
    }

    public void removeDeviceListener(String mac, BghPacketListener listener) {
        deviceListeners.remove(mac, listener);
    }

    /** Registra un listener que recibe todos los paquetes (lo usa el descubrimiento). */
    public void addGlobalListener(BghPacketListener listener) {
        globalListeners.add(listener);
    }

    public void removeGlobalListener(BghPacketListener listener) {
        globalListeners.remove(listener);
    }

    public void send(InetAddress address, byte[] packet) throws IOException {
        openSocket().send(new DatagramPacket(packet, packet.length, address, PORT_DEVICE));
    }

    public void broadcastDiscovery() throws IOException {
        send(InetAddress.getByName(BROADCAST_ADDRESS), BghPacket.enumerateAll());
    }

    private synchronized DatagramSocket openSocket() throws IOException {
        DatagramSocket current = socket;
        if (current != null && !current.isClosed()) {
            return current;
        }
        DatagramSocket created = bindSocket();
        socket = created;
        Thread receiver = new Thread(() -> receiveLoop(created), "OH-binding-bgh-udp");
        receiver.setDaemon(true);
        receiver.start();
        logger.debug("Socket UDP abierto en el puerto {}", PORT_LOCAL);
        return created;
    }

    private DatagramSocket bindSocket() throws SocketException {
        DatagramSocket created = new DatagramSocket(null);
        try {
            created.setBroadcast(true);
            created.bind(new InetSocketAddress(PORT_LOCAL));
            return created;
        } catch (SocketException e) {
            created.close();
            throw new SocketException("No se pudo abrir el puerto UDP " + PORT_LOCAL + " (¿está en uso?): "
                    + e.getMessage());
        }
    }

    private void receiveLoop(DatagramSocket socket) {
        byte[] buffer = new byte[BUFFER_SIZE];
        while (!socket.isClosed()) {
            DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
            if (receive(socket, datagram)) {
                BghPacket.parse(Arrays.copyOf(datagram.getData(), datagram.getLength()))
                        .filter(this::isFirstCopy)
                        .ifPresent(packet -> dispatch(packet, datagram.getAddress()));
            }
        }
        logger.debug("Socket UDP cerrado");
    }

    private boolean receive(DatagramSocket socket, DatagramPacket datagram) {
        try {
            socket.receive(datagram);
            return true;
        } catch (IOException e) {
            if (!socket.isClosed()) {
                logger.debug("Error recibiendo por UDP: {}", e.getMessage());
            }
            return false;
        }
    }

    /** Cada respuesta del equipo llega duplicada: se descarta la copia por (MAC, comando, secuencia). */
    private boolean isFirstCopy(BghPacket packet) {
        long now = System.currentTimeMillis();
        recentPackets.values().removeIf(seen -> now - seen > DEDUP_WINDOW_MS);
        String key = packet.sourceMac() + "/" + packet.command() + "/" + packet.sequence();
        return recentPackets.putIfAbsent(key, now) == null;
    }

    private void dispatch(BghPacket packet, InetAddress sender) {
        logger.trace("RX {} de {} cmd=0x{} seq={} ep={} payload={}", sender.getHostAddress(), packet.sourceMac(),
                Integer.toHexString(packet.command()), packet.sequence(), packet.sourceEndpoint(),
                HexFormat.of().formatHex(packet.payload()));
        globalListeners.forEach(listener -> notify(listener, packet, sender));
        Optional.ofNullable(deviceListeners.get(packet.sourceMac()))
                .ifPresent(listener -> notify(listener, packet, sender));
    }

    private void notify(BghPacketListener listener, BghPacket packet, InetAddress sender) {
        try {
            listener.onPacket(packet, sender);
        } catch (RuntimeException e) {
            logger.warn("Error procesando paquete de {}: {}", packet.sourceMac(), e.getMessage(), e);
        }
    }
}
