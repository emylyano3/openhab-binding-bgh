package org.openhab.binding.bgh.internal;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Constantes del binding BGH Smart Control.
 */
@NonNullByDefault
public final class BghBindingConstants {

    public static final String BINDING_ID = "bgh";

    public static final ThingTypeUID THING_TYPE_AC = new ThingTypeUID(BINDING_ID, "ac");
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_AC);

    public static final String CHANNEL_POWER = "power";
    public static final String CHANNEL_MODE = "mode";
    public static final String CHANNEL_FAN_SPEED = "fanSpeed";
    public static final String CHANNEL_TARGET_TEMPERATURE = "targetTemperature";
    public static final String CHANNEL_CURRENT_TEMPERATURE = "currentTemperature";
    public static final String CHANNEL_TURBO = "turbo";
    public static final String CHANNEL_SWING_HORIZONTAL = "swingHorizontal";
    public static final String CHANNEL_SWING_VERTICAL = "swingVertical";

    public static final String CONFIG_MAC_ADDRESS = "macAddress";
    public static final String CONFIG_IP_ADDRESS = "ipAddress";

    /** Rango que admite la app oficial. */
    public static final double MIN_SETPOINT_CELSIUS = 17;
    public static final double MAX_SETPOINT_CELSIUS = 30;

    private BghBindingConstants() {
    }
}
