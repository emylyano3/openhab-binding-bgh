package org.openhab.binding.bgh.internal.handler;

import static org.openhab.binding.bgh.internal.BghBindingConstants.*;
import static org.openhab.binding.bgh.internal.protocol.BghProtocol.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bgh.internal.BghAcConfiguration;
import org.openhab.binding.bgh.internal.protocol.BghPacket;
import org.openhab.binding.bgh.internal.protocol.DeviceInfo;
import org.openhab.binding.bgh.internal.protocol.FanSpeed;
import org.openhab.binding.bgh.internal.protocol.HvacMode;
import org.openhab.binding.bgh.internal.protocol.HvacStatus;
import org.openhab.binding.bgh.internal.protocol.HvacTarget;
import org.openhab.binding.bgh.internal.protocol.MacAddresses;
import org.openhab.binding.bgh.internal.transport.BghPacketListener;
import org.openhab.binding.bgh.internal.transport.BghUdpTransport;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler de un aire BGH Smart Control.
 *
 * <ul>
 * <li>Consulta el estado ({@code Poll}) cada {@code refreshInterval} y se suscribe a los cambios espontáneos.</li>
 * <li>Cada comando de openHAB se traduce a un {@code SetMode} completo (modo, ventilador, flags y setpoint), partiendo
 * del último estado conocido, y se reintenta si el equipo no responde con ACK.</li>
 * <li>La IP se toma de la configuración o se descubre por broadcast, y se actualiza si el equipo responde desde otra.</li>
 * </ul>
 */
@NonNullByDefault
public class BghAcHandler extends BaseThingHandler implements BghPacketListener {

    private static final long ACK_TIMEOUT_MS = 1500;
    private static final int SEND_ATTEMPTS = 2;
    private static final long STATUS_TIMEOUT_MS = 3000;
    private static final int MIN_REFRESH_S = 5;
    private static final int MIN_SUBSCRIBE_S = 10;
    private static final int MISSED_REFRESHES_BEFORE_OFFLINE = 3;

    private final Logger logger = LoggerFactory.getLogger(BghAcHandler.class);
    private final BghUdpTransport transport;
    private final Object commandLock = new Object();

    private BghAcConfiguration config = new BghAcConfiguration();
    private String mac = "";
    private byte[] macBytes = new byte[6];

    private volatile @Nullable InetAddress address;
    private volatile @Nullable HvacTarget target;
    private volatile int lastActiveMode = HvacMode.COOL.code();
    private volatile long lastSeen;
    private volatile CompletableFuture<HvacStatus> nextStatus = new CompletableFuture<>();
    private volatile CompletableFuture<Boolean> nextAck = new CompletableFuture<>();

    private @Nullable ScheduledFuture<?> refreshJob;
    private @Nullable ScheduledFuture<?> subscribeJob;

    public BghAcHandler(Thing thing, BghUdpTransport transport) {
        super(thing);
        this.transport = transport;
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void initialize() {
        config = getConfigAs(BghAcConfiguration.class);
        Optional<String> normalizedMac = MacAddresses.normalize(config.macAddress);
        if (normalizedMac.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "Dirección MAC inválida: '" + config.macAddress + "'");
            return;
        }
        mac = normalizedMac.get();
        macBytes = MacAddresses.toBytes(mac);
        address = parseAddress(config.ipAddress).orElse(null);
        target = null;
        nextStatus = new CompletableFuture<>();
        lastSeen = System.currentTimeMillis();

        transport.addDeviceListener(mac, this);
        updateStatus(ThingStatus.UNKNOWN);

        int refreshSeconds = Math.max(MIN_REFRESH_S, config.refreshInterval);
        int subscribeSeconds = Math.max(MIN_SUBSCRIBE_S, config.subscribeInterval);
        subscribeJob = scheduler.scheduleWithFixedDelay(this::subscribe, 0, subscribeSeconds, TimeUnit.SECONDS);
        refreshJob = scheduler.scheduleWithFixedDelay(this::refresh, 1, refreshSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        Optional.ofNullable(refreshJob).ifPresent(job -> job.cancel(true));
        Optional.ofNullable(subscribeJob).ifPresent(job -> job.cancel(true));
        refreshJob = null;
        subscribeJob = null;
        transport.removeDeviceListener(mac, this);
    }

    // ------------------------------------------------------------------ comandos desde openHAB

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            Optional.ofNullable(address).ifPresent(this::sendPoll);
            return;
        }
        scheduler.execute(() -> applyCommand(channelUID.getId(), command));
    }

    private void applyCommand(String channelId, Command command) {
        synchronized (commandLock) {
            Optional<HvacTarget> current = currentTarget();
            if (current.isEmpty()) {
                logger.warn("{}: sin estado del equipo, se descarta {}={}", mac, channelId, command);
                return;
            }
            current.flatMap(base -> applyTo(base, channelId, command))
                    .ifPresentOrElse(this::sendTarget,
                            () -> logger.warn("{}: comando no soportado {}={}", mac, channelId, command));
        }
    }

    private Optional<HvacTarget> applyTo(HvacTarget base, String channelId, Command command) {
        return switch (channelId) {
            case CHANNEL_POWER -> powerOf(command).map(on -> base.withMode(on ? lastActiveMode : HvacMode.OFF.code()));
            case CHANNEL_MODE -> HvacMode.fromId(command.toString()).map(HvacMode::code).map(base::withMode);
            case CHANNEL_FAN_SPEED -> FanSpeed.fromId(command.toString()).map(FanSpeed::code).map(base::withFan);
            case CHANNEL_TARGET_TEMPERATURE -> celsiusOf(command).filter(this::isValidSetpoint).map(base::withSetpoint);
            default -> Optional.empty();
        };
    }

    private Optional<Boolean> powerOf(Command command) {
        return command instanceof OnOffType onOff ? Optional.of(onOff == OnOffType.ON) : Optional.empty();
    }

    private Optional<Double> celsiusOf(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            return Optional.ofNullable(quantity.toUnit(SIUnits.CELSIUS)).map(QuantityType::doubleValue);
        }
        if (command instanceof DecimalType decimal) {
            return Optional.of(decimal.doubleValue());
        }
        return Optional.empty();
    }

    private boolean isValidSetpoint(double celsius) {
        boolean valid = celsius >= MIN_SETPOINT_CELSIUS && celsius <= MAX_SETPOINT_CELSIUS;
        if (!valid) {
            logger.warn("{}: setpoint {} °C fuera de rango {}-{}", mac, celsius, MIN_SETPOINT_CELSIUS,
                    MAX_SETPOINT_CELSIUS);
        }
        return valid;
    }

    private Optional<HvacTarget> currentTarget() {
        return Optional.ofNullable(target).or(this::awaitFirstStatus);
    }

    private Optional<HvacTarget> awaitFirstStatus() {
        CompletableFuture<HvacStatus> pending = nextStatus;
        Optional.ofNullable(address).ifPresent(this::sendPoll);
        return await(pending, STATUS_TIMEOUT_MS).map(HvacStatus::toTarget);
    }

    private void sendTarget(HvacTarget desired) {
        InetAddress ip = address;
        if (ip == null) {
            logger.warn("{}: IP desconocida, no se puede enviar el comando", mac);
            return;
        }
        byte[] packet = BghPacket.setMode(macBytes, config.endpoint, desired);
        boolean acknowledged = IntStream.range(0, SEND_ATTEMPTS).anyMatch(attempt -> sendAndAwaitAck(ip, packet));
        if (!acknowledged) {
            logger.warn("{}: el equipo no confirmó el comando (sin ACK)", mac);
            return;
        }
        target = desired;
        rememberActiveMode(desired.mode());
        sendPoll(ip);
    }

    private boolean sendAndAwaitAck(InetAddress ip, byte[] packet) {
        CompletableFuture<Boolean> ack = new CompletableFuture<>();
        nextAck = ack;
        return send(ip, packet) && await(ack, ACK_TIMEOUT_MS).orElse(false);
    }

    // ------------------------------------------------------------------ tareas periódicas

    private void refresh() {
        Optional.ofNullable(address).ifPresentOrElse(this::sendPoll, this::broadcastDiscovery);
        checkAlive();
    }

    private void subscribe() {
        Optional.ofNullable(address).ifPresent(ip -> send(ip, BghPacket.subscribe(macBytes)));
    }

    private void checkAlive() {
        long silentMs = System.currentTimeMillis() - lastSeen;
        long limitMs = TimeUnit.SECONDS.toMillis(
                (long) Math.max(MIN_REFRESH_S, config.refreshInterval) * MISSED_REFRESHES_BEFORE_OFFLINE);
        if (silentMs <= limitMs) {
            return;
        }
        if (getThing().getStatus() != ThingStatus.OFFLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Sin respuesta del equipo hace " + TimeUnit.MILLISECONDS.toSeconds(silentMs) + " s");
        }
        // Puede haber cambiado de IP: el broadcast lo vuelve a ubicar.
        broadcastDiscovery();
    }

    private void sendPoll(InetAddress ip) {
        send(ip, BghPacket.poll(macBytes, config.endpoint));
    }

    private void broadcastDiscovery() {
        try {
            transport.broadcastDiscovery();
        } catch (IOException e) {
            reportTransportError(e);
        }
    }

    private boolean send(InetAddress ip, byte[] packet) {
        try {
            transport.send(ip, packet);
            return true;
        } catch (IOException e) {
            reportTransportError(e);
            return false;
        }
    }

    private void reportTransportError(IOException e) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
    }

    // ------------------------------------------------------------------ paquetes del equipo

    @Override
    public void onPacket(BghPacket packet, InetAddress sender) {
        lastSeen = System.currentTimeMillis();
        trackAddress(sender);
        if (getThing().getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.ONLINE);
        }
        switch (packet.command()) {
            case CMD_ACK -> nextAck.complete(true);
            case CMD_NAK -> logger.debug("{}: NAK (endpoint {})", mac, packet.sourceEndpoint());
            case CMD_STATUS -> statusOf(packet).ifPresent(this::onStatus);
            case CMD_JOIN -> packet.deviceInfo().ifPresent(this::onDeviceInfo);
            case CMD_DEBUG -> logger.debug("{}: debug '{}'", mac, packet.payloadText());
            default -> logger.trace("{}: comando 0x{} ignorado", mac, Integer.toHexString(packet.command()));
        }
    }

    private Optional<HvacStatus> statusOf(BghPacket packet) {
        return Optional.of(packet)
                .filter(p -> p.sourceEndpoint() == config.endpoint)
                .map(BghPacket::payload)
                .flatMap(HvacStatus::parse);
    }

    private void trackAddress(InetAddress sender) {
        if (!sender.equals(address)) {
            logger.info("{}: IP del equipo {}", mac, sender.getHostAddress());
            address = sender;
        }
    }

    private void onStatus(HvacStatus status) {
        target = status.toTarget();
        rememberActiveMode(status.mode());
        CompletableFuture<HvacStatus> completed = nextStatus;
        nextStatus = new CompletableFuture<>();
        completed.complete(status);
        publish(status);
    }

    private void publish(HvacStatus status) {
        updateState(CHANNEL_POWER, OnOffType.from(status.isOn()));
        updateState(CHANNEL_MODE, new StringType(HvacMode.describe(status.mode())));
        updateState(CHANNEL_FAN_SPEED, new StringType(FanSpeed.describe(status.fan())));
        updateState(CHANNEL_TARGET_TEMPERATURE, new QuantityType<>(status.desiredCelsius(), SIUnits.CELSIUS));
        updateState(CHANNEL_CURRENT_TEMPERATURE, new QuantityType<>(status.measuredCelsius(), SIUnits.CELSIUS));
        updateState(CHANNEL_TURBO, OnOffType.from(status.hasFlag(FLAG_TURBO)));
        updateState(CHANNEL_SWING_HORIZONTAL, OnOffType.from(status.hasFlag(FLAG_SWING_H)));
        updateState(CHANNEL_SWING_VERTICAL, OnOffType.from(status.hasFlag(FLAG_SWING_V)));
    }

    private void onDeviceInfo(DeviceInfo info) {
        updateProperty(Thing.PROPERTY_MODEL_ID, info.model());
        updateProperty(Thing.PROPERTY_FIRMWARE_VERSION, info.firmware());
        updateProperty(Thing.PROPERTY_MAC_ADDRESS, mac);
    }

    private void rememberActiveMode(int mode) {
        if (mode != HvacMode.OFF.code()) {
            lastActiveMode = mode;
        }
    }

    // ------------------------------------------------------------------ utilidades

    private Optional<InetAddress> parseAddress(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(text.trim()));
        } catch (UnknownHostException e) {
            logger.warn("{}: IP inválida '{}', se buscará por broadcast", config.macAddress, text);
            return Optional.empty();
        }
    }

    private static <T> Optional<T> await(CompletableFuture<T> future, long timeoutMs) {
        try {
            return Optional.of(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException | ExecutionException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
