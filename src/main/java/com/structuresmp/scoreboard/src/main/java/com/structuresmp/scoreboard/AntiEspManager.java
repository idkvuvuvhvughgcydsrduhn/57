package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-ESP for player visibility, only in the worlds listed in anti-esp.worlds (default: Survival).
 *
 * A player is hidden from another player when:
 *  - they are farther than anti-esp.hide-beyond-blocks, or
 *  - they are farther than anti-esp.los-grace-blocks AND none of their three points
 *    (feet, body, head) can be seen through open blocks.
 * Players with the permission structuresmp.antiesp.bypass (OP) are never hidden and never hide.
 *
 * Note: this hides players from each other on the server side. Ore/block x-ray is handled separately
 * by Paper's built-in anti-xray (see paper-global.yml).
 */
public final class AntiEspManager implements Listener {

    private static final String BYPASS = "structuresmp.antiesp.bypass";

    private final JavaPlugin plugin;
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>(); // viewer -> players they cannot see
    private BukkitTask task;

    private boolean enabled = true;
    private List<String> worlds = new ArrayList<>();
    private double hideBeyond = 48;
    private double losGrace = 6;

    public AntiEspManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.getConfig().addDefault("anti-esp.enabled", true);
        plugin.getConfig().addDefault("anti-esp.worlds", List.of("Survival"));
        plugin.getConfig().addDefault("anti-esp.hide-beyond-blocks", 48);
        plugin.getConfig().addDefault("anti-esp.los-grace-blocks", 6);
        plugin.getConfig().addDefault("anti-esp.check-ticks", 10);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("anti-esp.enabled", true);
        worlds = cfg.getStringList("anti-esp.worlds");
        hideBeyond = Math.max(8, cfg.getDouble("anti-esp.hide-beyond-blocks", 48));
        losGrace = Math.max(0, cfg.getDouble("anti-esp.los-grace-blocks", 6));
        long interval = Math.max(1, cfg.getLong("anti-esp.check-ticks", 10));

        if (task != null) task.cancel();
        task = null;
        if (enabled) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
        } else {
            showAll();
        }
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        showAll();
    }

    private boolean watched(World w) {
        if (w == null) return false;
        for (String name : worlds) {
            if (name.equalsIgnoreCase(w.getName())) return true;
        }
        return false;
    }

    private void tick() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : online) {
            if (!watched(viewer.getWorld()) || viewer.hasPermission(BYPASS)) {
                unhideAllFor(viewer);
                continue;
            }
            Set<UUID> mine = hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            for (Player target : online) {
                if (target.equals(viewer)) continue;
                boolean hide = !target.getWorld().equals(viewer.getWorld())
                        ? false
                        : shouldHide(viewer, target) && !target.hasPermission(BYPASS);
                boolean isHidden = mine.contains(target.getUniqueId());
                if (hide && !isHidden) {
                    viewer.hideEntity(plugin, target);
                    mine.add(target.getUniqueId());
                } else if (!hide && isHidden) {
                    viewer.showEntity(plugin, target);
                    mine.remove(target.getUniqueId());
                }
            }
            if (mine.isEmpty()) hidden.remove(viewer.getUniqueId());
        }
    }

    private boolean shouldHide(Player viewer, Player target) {
        if (!target.getWorld().equals(viewer.getWorld())) return false;
        Location eye = viewer.getEyeLocation();
        Location feet = target.getLocation();
        double dist = eye.distance(feet);
        if (dist > hideBeyond) return true;
        if (dist <= losGrace) return false;

        Location[] points = {
                target.getEyeLocation(),
                feet.clone().add(0, 1.0, 0),
                feet.clone().add(0, 0.1, 0)
        };
        for (Location point : points) {
            if (!blocked(eye, point)) return false; // at least one point is visible
        }
        return true;
    }

    /** True when solid blocks stand between the two points. */
    private static boolean blocked(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.01) return false;
        RayTraceResult hit = from.getWorld().rayTraceBlocks(from, dir.normalize(), len,
                FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null;
    }

    private void unhideAllFor(Player viewer) {
        Set<UUID> set = hidden.remove(viewer.getUniqueId());
        if (set == null) return;
        for (UUID id : set) {
            Player t = Bukkit.getPlayer(id);
            if (t != null) viewer.showEntity(plugin, t);
        }
    }

    private void showAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) unhideAllFor(viewer);
        hidden.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hidden.remove(id);
        for (Set<UUID> set : hidden.values()) set.remove(id);
    }
}
