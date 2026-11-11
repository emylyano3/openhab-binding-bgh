package org.openhab.binding.bgh.internal.discovery;

import static org.openhab.binding.bgh.internal.BghBindingConstants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bgh.internal.protocol.BghPacket;
import org.openhab.binding.bgh.internal.protocol.DeviceInfo;
import org.openhab.binding.bgh.internal.protocol.MacAddresses;
import org.openhab.binding.bgh.internal.transport.BghPacketListener;
import org.openhab.binding.bgh.internal.transport.BghUdpTransport;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.DiscoveryService;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Descubre aires BGH con un broadcast Enumerate_All. Cada equipo responde con su modelo y firmware.
 */
@NonNullByDefault
@Component(service = DiscoveryService.class, configurationPid = "discovery.bgh")
public class BghDiscoveryService extends AbstractDiscoveryService implements BghPacketListener {

    private static final int SEARCH_TIMEOUT_S = 5;
    private static final long BACKGROUND_INTERVAL_MIN = 5;

    private final Logger logger = LoggerFactory.getLogger(BghDiscoveryService.class);
    private final BghUdpTransport transport;

    private @Nullable ScheduledFuture<?> backgroundJob;

    @Activate
    public BghDiscoveryService(@Reference BghUdpTransport transport) {
        super(SUPPORTED_THING_TYPES, SEARCH_TIMEOUT_S, true);
        this.transport = transport;
    }

    @Override
    @Activate
    protected void activate(@Nullable Map<String, Object> configProperties) {
        transport.addGlobalListener(this);
        super.activate(configProperties);
    }

    @Override
    @Modified
    protected void modified(@Nullable Map<String, Object> configProperties) {
        super.modified(configProperties);
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
        transport.removeGlobalListener(this);
    }

    @Override
    protected void startScan() {
        broadcast();
    }

    @Override
    protected void startBackgroundDiscovery() {
        backgroundJob = scheduler.scheduleWithFixedDelay(this::broadcast, 0, BACKGROUND_INTERVAL_MIN,
                TimeUnit.MINUTES);
    }

    @Override
    protected void stopBackgroundDiscovery() {
        Optional.ofNullable(backgroundJob).ifPresent(job -> job.cancel(true));
        backgroundJob = null;
    }

    @Override
    public void onPacket(BghPacket packet, InetAddress sender) {
        packet.deviceInfo().map(info -> toResult(packet.sourceMac(), sender, info)).ifPresent(this::thingDiscovered);
    }

    private void broadcast() {
        try {
            transport.broadcastDiscovery();
        } catch (IOException e) {
            logger.warn("No se pudo enviar el broadcast de descubrimiento: {}", e.getMessage());
        }
    }

    private DiscoveryResult toResult(String mac, InetAddress sender, DeviceInfo info) {
        String ip = sender.getHostAddress();
        return DiscoveryResultBuilder.create(new ThingUID(THING_TYPE_AC, MacAddresses.toId(mac)))
                .withLabel("Aire " + info.model() + " (" + ip + ")")
                .withProperty(CONFIG_MAC_ADDRESS, mac)
                .withProperty(CONFIG_IP_ADDRESS, ip)
                .withProperty(Thing.PROPERTY_MODEL_ID, info.model())
                .withProperty(Thing.PROPERTY_FIRMWARE_VERSION, info.firmware())
                .withRepresentationProperty(CONFIG_MAC_ADDRESS)
                .build();
    }
}
