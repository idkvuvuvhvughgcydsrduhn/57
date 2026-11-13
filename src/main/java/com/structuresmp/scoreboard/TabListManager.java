package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Header and footer of the TAB player list:
 *
 *      Structure Smp           (purple gradient wave, same colours as the scoreboard title)
 *   -------------------------
 *        [player names]
 *   -------------------------
 *   structuresmp.playwithbao.com
 *   19583
 *
 * Everything is editable in config.yml under "tablist".
 */
public final class TabListManager {

    private final JavaPlugin plugin;
    private final long start = System.currentTimeMillis();
    private BukkitTask task;

    private boolean enabled = true;
    private String titleText = "Structure Smp";
    private String separator = "&#6B21A8-------------------------------";
    private List<String> footer = new ArrayList<>();

    // gradient settings (shared with the sidebar title)
    private boolean animate = true;
    private int darkRgb = 0x4B0A8F;
    private int lightRgb = 0xE3B5FF;
    private double cycleSeconds = 3.0;
    private boolean bold = true;

    public TabListManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.getConfig().addDefault("tablist.enabled", true);
        plugin.getConfig().addDefault("tablist.title", "Structure Smp");
        plugin.getConfig().addDefault("tablist.separator", "&#6B21A8-------------------------------");
        plugin.getConfig().addDefault("tablist.footer", java.util.Arrays.asList(
                "&#A855F7structuresmp.playwithbao.com",
                "&#D8B4FE19583"));
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("tablist.enabled", true);
        titleText = cfg.getString("tablist.title", "Structure Smp");
        separator = cfg.getString("tablist.separator", "&#6B21A8-------------------------------");
        footer = cfg.getStringList("tablist.footer");

        animate = cfg.getBoolean("animated-title.enabled", true);
        darkRgb = parseColor(cfg.getString("animated-title.dark-color", "#4B0A8F"), 0x4B0A8F);
        lightRgb = parseColor(cfg.getString("animated-title.light-color", "#E3B5FF"), 0xE3B5FF);
        cycleSeconds = Math.max(0.2, cfg.getDouble("animated-title.cycle-seconds", 3.0));
        bold = cfg.getBoolean("animated-title.bold", true);

        if (task != null) task.cancel();
        task = null;
        if (enabled) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 4L);
        } else {
            clear();
        }
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        clear();
    }

    private void clear() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        }
    }

    private void tick() {
        Component sep = Text.c(separator);
        Component header = Component.empty()
                .append(gradient(System.currentTimeMillis()))
                .append(Component.newline())
                .append(sep);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Component foot = Component.empty().append(sep);
            for (String line : footer) {
                foot = foot.append(Component.newline()).append(Text.c(fill(line, p)));
            }
            p.sendPlayerListHeaderAndFooter(header, foot);
        }
    }

    private static String fill(String line, Player p) {
        return line
                .replace("%player%", p.getName())
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("%ping%", String.valueOf(p.getPing()));
    }

    // ------------------------------------------------------------------ gradient

    private static int parseColor(String text, int fallback) {
        if (text == null) return fallback;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** One frame of the title: a dark-to-light purple wave travelling along the text. */
    private Component gradient(long now) {
        double shift = animate ? ((now - start) / 1000.0) / cycleSeconds : 0;
        int[] chars = titleText.codePoints().toArray();
        Component out = Component.empty();
        for (int i = 0; i < chars.length; i++) {
            double pos = (double) i / Math.max(1, chars.length);
            double v = 0.5 + 0.5 * Math.sin(2 * Math.PI * (pos - shift));
            Component part = Component.text(new String(Character.toChars(chars[i])),
                    TextColor.color(lerp(darkRgb, lightRgb, v)));
            if (bold) part = part.decorate(TextDecoration.BOLD);
            out = out.append(part);
        }
        return out;
    }
}
