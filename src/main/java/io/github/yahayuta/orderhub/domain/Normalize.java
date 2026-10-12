package io.github.yahayuta.orderhub.domain;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Turns the different ways marketplaces write the same thing into one form.
 *
 * <p>Japanese marketplace exports mix full-width and half-width characters, write money as
 * "1,980円" and send local times without an offset. Everything goes through NFKC first, so
 * "２，４００円" and "2,400" end up as the same number.
 */
public final class Normalize {

    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter SLASHED = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private Normalize() {
    }

    public static String text(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
    }

    public static String sku(String value) {
        return text(value).toUpperCase(Locale.ROOT);
    }

    /** Parses "1,980円", "¥1980", "１，９８０" or 1980 into 1980. */
    public static long yen(String value) {
        String digits = text(value).replace(",", "").replace("円", "").replace("¥", "").replace(" ", "");
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("empty amount");
        }
        return Long.parseLong(digits);
    }

    public static int quantity(String value) {
        return Math.toIntExact(yen(value));
    }

    /** "2026/10/01 10:05:00" in Japan time, as some marketplaces send it, to an instant. */
    public static Instant slashedJst(String value) {
        return LocalDateTime.parse(text(value), SLASHED).atZone(JST).toInstant();
    }

    public static String toSlashedJst(Instant instant) {
        return SLASHED.format(instant.atZone(JST));
    }
}
