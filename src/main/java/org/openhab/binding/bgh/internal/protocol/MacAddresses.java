package org.openhab.binding.bgh.internal.protocol;

import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Conversión de direcciones MAC entre texto ({@code AC:CF:23:12:34:56}) y bytes.
 */
@NonNullByDefault
public final class MacAddresses {

    private static final Pattern HEX_12 = Pattern.compile("[0-9A-Fa-f]{12}");
    private static final HexFormat COLON_FORMAT = HexFormat.ofDelimiter(":").withUpperCase();

    private MacAddresses() {
    }

    /** Acepta {@code AC:CF:...}, {@code ac-cf-...} o {@code accf...} y devuelve {@code AC:CF:...}. */
    public static Optional<String> normalize(String text) {
        return Optional.of(text.trim().replaceAll("[:\\-]", ""))
                .filter(hex -> HEX_12.matcher(hex).matches())
                .map(HexFormat.of()::parseHex)
                .map(COLON_FORMAT::formatHex);
    }

    public static String format(byte[] data, int offset) {
        return COLON_FORMAT.formatHex(data, offset, offset + 6);
    }

    public static byte[] toBytes(String normalizedMac) {
        return COLON_FORMAT.parseHex(normalizedMac);
    }

    /** Identificador apto para un ThingUID: {@code accf23123456}. */
    public static String toId(String normalizedMac) {
        return normalizedMac.replace(":", "").toLowerCase(Locale.ROOT);
    }
}
