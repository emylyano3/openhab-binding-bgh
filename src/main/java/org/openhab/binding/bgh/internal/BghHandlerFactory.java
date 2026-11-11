package org.openhab.binding.bgh.internal;

import static org.openhab.binding.bgh.internal.BghBindingConstants.SUPPORTED_THING_TYPES;
import static org.openhab.binding.bgh.internal.BghBindingConstants.THING_TYPE_AC;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bgh.internal.handler.BghAcHandler;
import org.openhab.binding.bgh.internal.transport.BghUdpTransport;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * Crea los handlers de los aires BGH.
 */
@NonNullByDefault
@Component(configurationPid = "binding.bgh", service = ThingHandlerFactory.class)
public class BghHandlerFactory extends BaseThingHandlerFactory {

    private final BghUdpTransport transport;

    @Activate
    public BghHandlerFactory(@Reference BghUdpTransport transport) {
        this.transport = transport;
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        return THING_TYPE_AC.equals(thing.getThingTypeUID()) ? new BghAcHandler(thing, transport) : null;
    }
}
