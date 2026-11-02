package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Purple particle trail while players walk (small) and sprint (bigger, with a puff of cloud). */
public final class MovementEffects {

    private static final Color DARK = Color.fromRGB(0x4B0A8F);
    private static final Color LIGHT = Color.fromRGB(0xE3B5FF);
    private static final Particle.DustTransition WALK_DUST = new Particle.DustTransition(DARK, LIGHT, 0.8f);
    private static final Particle.DustTransition SPRINT_DUST = new Particle.DustTransition(DARK, LIGHT, 1.3f);

    private final JavaPlugin plugin;
    private final Map<UUID, Location> last = new HashMap<>();
    private BukkitTask task;
    private boolean walk = true;
    private boolean sprint = true;

    public MovementEffects(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        if (task != null) task.cancel();
        task = null;
        last.clear();
        if (!plugin.getConfig().getBoolean("effects.enabled", true)) return;
        walk = plugin.getConfig().getBoolean("effects.walk", true);
        sprint = plugin.getConfig().getBoolean("effects.sprint", true);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 3L);
    }

    public void stop() {
        if (task != null) task.cancel();
        last.clear();
    }

    private void tick() {
        last.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);

        for (Player p : Bukkit.getOnlinePlayers()) {
            Location now = p.getLocation();
            Location before = last.put(p.getUniqueId(), now);
            if (before == null || before.getWorld() == null || !before.getWorld().equals(now.getWorld())) continue;

            double dx = now.getX() - before.getX();
            double dz = now.getZ() - before.getZ();
            if (dx * dx + dz * dz < 0.01) continue; // standing still

            if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying() || p.isGliding()
                    || p.isSwimming() || p.isSneaking() || p.isInsideVehicle()
                    || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                continue;
            }

            Location feet = now.clone().add(0, 0.1, 0);
            if (p.isSprinting()) {
                if (sprint) {
                    p.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, feet, 6, 0.25, 0.05, 0.25, 0, SPRINT_DUST);
                    p.getWorld().spawnParticle(Particle.CLOUD, feet, 1, 0.15, 0.02, 0.15, 0.01);
                }
            } else if (walk) {
                p.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, feet, 2, 0.2, 0.02, 0.2, 0, WALK_DUST);
            }
        }
    }
}
