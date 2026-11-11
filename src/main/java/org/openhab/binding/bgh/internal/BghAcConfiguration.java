package org.openhab.binding.bgh.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuración de un Thing {@code bgh:ac}. Los nombres coinciden con los parámetros de thing-types.xml.
 */
@NonNullByDefault
public class BghAcConfiguration {

    public String macAddress = "";
    public String ipAddress = "";
    public int refreshInterval = 30;
    public int subscribeInterval = 60;
    public int endpoint = 1;
}
