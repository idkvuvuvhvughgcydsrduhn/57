package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;

import java.util.Locale;

public final class Text {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private Text() {
    }

    /** "&a..." color codes to a Component. */
    public static Component c(String s) {
        return LEGACY.deserialize(s);
    }

    /** Same, but without the default italic used by item names and lore. */
    public static Component lore(String s) {
        return c(s).decoration(TextDecoration.ITALIC, false);
    }

    public static String money(double v) {
        return v == Math.floor(v)
                ? String.format(Locale.US, "%,.0f", v)
                : String.format(Locale.US, "%,.2f", v);
    }

    public static String pretty(Material m) {
        return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Parses 1500, 1.5k, 2m, 3b. Returns -1 when the text is not a valid amount. */
    public static double parseAmount(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "");
        double mult = 1;
        if (s.endsWith("k")) {
            mult = 1_000d;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000d;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("b")) {
            mult = 1_000_000_000d;
            s = s.substring(0, s.length() - 1);
        }
        try {
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
            return Math.round(v * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String duration(long millis) {
        long minutes = Math.max(0, millis / 60000);
        return (minutes / 60) + " ชม. " + (minutes % 60) + " น.";
    }
}
