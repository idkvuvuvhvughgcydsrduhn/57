package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Show for the person who receives /pay. The bigger the amount, the bigger the show.
 *   level = floor(log10(amount)) - 5, clamped to 0..6   (1M = 1, 1B = 3, 100B = 6)
 * Layers: gradient title, rising spiral beam, expanding shockwave rings, firework bursts,
 * lightning visual and a finale. Everything is visual only (no damage, no world changes).
 */
public final class PayEffects {

    private static JavaPlugin plugin;
    private static final int MAX_LEVEL = 6;

    private PayEffects() {
    }

    public static void init(JavaPlugin p) {
        plugin = p;
    }

    public static void play(Player receiver, double amount) {
        play(receiver, amount, null);
    }

    public static void play(Player receiver, double amount, String fromName) {
        if (plugin == null || receiver == null || amount <= 0) return;
        final int level = levelOf(amount);
        final String money = compact(amount);

        int[][] palette = level >= 5 ? new int[][] {{0xFFD54A, 0xFF5555}}
                : level >= 3 ? new int[][] {{0xA855F7, 0xFF6EC7}}
                : new int[][] {{0x55FF55, 0xAAFFAA}};
        Component title = gradient("+$" + money, palette[0][0], palette[0][1]);
        String sub = fromName == null ? "ได้รับเงิน" : "ได้รับเงินจาก " + fromName;
        receiver.showTitle(Title.title(title, Text.c("&7" + sub),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600 + level * 200L), Duration.ofMillis(500))));

        World w = receiver.getWorld();
        Location base = receiver.getLocation();
        receiver.playSound(base, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
        if (level >= 2) receiver.playSound(base, Sound.BLOCK_END_PORTAL_SPAWN, 0.5f + 0.1f * level, 1.2f);
        if (level >= 4) receiver.playSound(base, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.0f);

        final int steps = 30 + level * 12;
        final int[] t = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            if (!receiver.isOnline() || t[0] >= steps) {
                task[0].cancel();
                if (receiver.isOnline()) finale(receiver, level);
                return;
            }
            Location loc = receiver.getLocation();
            int i = t[0]++;

            // 1) rising spiral beam around the player
            int arms = 2 + level / 2;
            for (int k = 0; k < arms; k++) {
                double a = i * 0.5 + k * (Math.PI * 2 / arms);
                double y = (i % 40) * 0.09;
                Location p = loc.clone().add(Math.cos(a) * 1.1, y, Math.sin(a) * 1.1);
                w.spawnParticle(level >= 3 ? Particle.END_ROD : Particle.HAPPY_VILLAGER, p, 1, 0, 0, 0, 0);
            }

            // 2) shockwave rings growing from the feet
            if (level >= 2 && i % 6 == 0) {
                double radius = 0.5 + ((i % 30) * 0.15);
                int seg = 16 + level * 2;
                for (int s = 0; s < seg; s++) {
                    double a = s * (Math.PI * 2 / seg);
                    Location p = loc.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius);
                    w.spawnParticle(level >= 4 ? Particle.FLAME : Particle.CLOUD, p, 1, 0, 0, 0, 0);
                }
            }

            // 3) firework bursts around the player
            if (level >= 3 && i % Math.max(4, 12 - level) == 0) {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                Location burst = loc.clone().add(r.nextDouble(-2.5, 2.5), r.nextDouble(1.5, 3.5), r.nextDouble(-2.5, 2.5));
                w.spawnParticle(Particle.FIREWORK, burst, 10 + level * 5, 0.3, 0.3, 0.3, 0.15);
                receiver.playSound(burst, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 0.9f + r.nextFloat() * 0.4f);
            }

            // 4) dragon breath halo for the top tiers
            if (level >= 5 && i % 5 == 0) {
                w.spawnParticle(Particle.DRAGON_BREATH, loc.clone().add(0, 1.0, 0), 12, 1.0, 0.8, 1.0, 0.02);
            }

            // 5) lightning visual (no damage) at the start of big payments
            if (level >= 5 && i == 4) {
                Location strike = loc.clone().add(ThreadLocalRandom.current().nextDouble(-3, 3), 0,
                        ThreadLocalRandom.current().nextDouble(-3, 3));
                w.strikeLightningEffect(strike);
            }

            // 6) chimes that rise in pitch
            if (i % Math.max(2, 5 - level / 2) == 0) {
                receiver.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.9f, Math.min(2.0f, 0.6f + i * 0.04f));
            }
            if (level >= 5 && i % 7 == 0) {
                receiver.playSound(loc, Sound.BLOCK_BELL_USE, 1.0f, 0.6f + 0.05f * (i % 10));
            }
        }, 1L, 2L);
    }

    /** Big closing flash for the higher tiers. */
    private static void finale(Player p, int level) {
        if (level < 2) return;
        World w = p.getWorld();
        Location loc = p.getLocation().add(0, 1.2, 0);
        w.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 30 + level * 10, 0.8, 0.8, 0.8, 0.2);
        if (level >= 3) {
            w.spawnParticle(Particle.FIREWORK, loc.clone().add(0, 1.5, 0), 40 + level * 10, 1.0, 1.0, 1.0, 0.25);
            p.playSound(loc, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, 1.0f, 1.0f);
        }
        if (level >= 5) {
            w.spawnParticle(Particle.EXPLOSION, loc, 2, 0.3, 0.3, 0.3, 0.0);
            p.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.4f, 1.4f);
        }
    }

    /** 0 for under 1M, 1 for 1M, 2 for 10M, 3 for 1B, ... 6 for 100B and above. */
    static int levelOf(double amount) {
        if (amount < 1) return 0;
        int lvl = (int) Math.floor(Math.log10(amount)) - 5;
        return Math.max(0, Math.min(MAX_LEVEL, lvl));
    }

    /** Text with a colour fade from one colour to another, one colour per character. */
    static Component gradient(String text, int from, int to) {
        int[] cps = text.codePoints().toArray();
        Component out = Component.empty();
        for (int i = 0; i < cps.length; i++) {
            double t = cps.length <= 1 ? 0 : (double) i / (cps.length - 1);
            out = out.append(Component.text(new String(Character.toChars(cps[i])),
                    TextColor.color(lerp(from, to, t))));
        }
        return out;
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Short money text: 1.5M, 2B, 100B. */
    static String compact(double v) {
        if (v >= 1e9) return trim(v / 1e9) + "B";
        if (v >= 1e6) return trim(v / 1e6) + "M";
        if (v >= 1e3) return trim(v / 1e3) + "K";
        return trim(v);
    }

    private static String trim(double v) {
        String s = String.format(Locale.US, "%.2f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
