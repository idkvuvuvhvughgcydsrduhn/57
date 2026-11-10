package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-ESP แบบพื้นฐาน: ซ่อนผู้เล่นที่มองไม่เห็น (มีบล็อกบัง) ไม่ให้เห็นชื่อ/ตัว
 * ใช้ hidePlayer/showPlayer — ไม่ต้องพึ่ง ProtocolLib
 *
 * จำกัด: กัน ESP ระดับทั่วไปได้ กัน cheat ขั้นสูง (packet-based) ไม่ได้ 100%
 */
public final class AntiEspManager implements Listener {

    private final JavaPlugin plugin;
    private BukkitTask task;

    private boolean enabled = true;
    private double maxDistance = 64.0;
    private int checkTicks = 10; // เช็คทุก กี่ tick
    private boolean hideSpectators = true;

    /** viewer -> set of hidden target UUIDs */
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();

    public AntiEspManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.getConfig().addDefault("anti-esp.enabled", true);
        plugin.getConfig().addDefault("anti-esp.max-distance", 64.0);
        plugin.getConfig().addDefault("anti-esp.check-ticks", 10);
        plugin.getConfig().addDefault("anti-esp.hide-spectators", true);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        enabled = plugin.getConfig().getBoolean("anti-esp.enabled", true);
        maxDistance = Math.max(8.0, plugin.getConfig().getDouble("anti-esp.max-distance", 64.0));
        checkTicks = Math.max(5, plugin.getConfig().getInt("anti-esp.check-ticks", 10));
        hideSpectators = plugin.getConfig().getBoolean("anti-esp.hide-spectators", true);

        if (task != null) task.cancel();
        task = null;

        // โชว์ทุกคนกลับก่อน แล้วค่อยเริ่มใหม่
        clearAll();

        if (enabled) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, checkTicks);
        }
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        clearAll();
    }

    private void clearAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Set<UUID> set = hidden.remove(viewer.getUniqueId());
            if (set == null) continue;
            for (UUID id : set) {
                Player t = Bukkit.getPlayer(id);
                if (t != null) viewer.showPlayer(plugin, t);
            }
        }
        hidden.clear();
    }

    private void tick() {
        if (!enabled) return;
        var online = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        for (Player viewer : online) {
            if (!viewer.isOnline()) continue;
            for (Player target : online) {
                if (viewer.equals(target)) continue;
                if (!target.isOnline()) continue;
                if (!viewer.getWorld().equals(target.getWorld())) {
                    ensureHidden(viewer, target, false);
                    continue;
                }

                boolean shouldSee = canSee(viewer, target);
                ensureHidden(viewer, target, !shouldSee);
            }
        }
    }

    /** true = มองเห็นได้ (ไม่ซ่อน) */
    private boolean canSee(Player viewer, Player target) {
        // OP / admin เห็นทุกคน
        if (viewer.hasPermission("structuresmp.antiesp.bypass")) return true;

        // spectator
        if (target.getGameMode() == GameMode.SPECTATOR) {
            return !hideSpectators || viewer.getGameMode() == GameMode.SPECTATOR
                    || viewer.hasPermission("structuresmp.antiesp.bypass");
        }

        Location eye = viewer.getEyeLocation();
        Location targetLoc = target.getEyeLocation();
        double dist = eye.distance(targetLoc);
        if (dist > maxDistance) return false;
        if (dist < 1.5) return true; // ใกล้มาก เห็นได้

        // raytrace จากตาผู้ดูไปยังเป้าหมาย
        Vector dir = targetLoc.toVector().subtract(eye.toVector());
        double length = dir.length();
        if (length < 0.1) return true;
        dir.normalize();

        RayTraceResult hit = viewer.getWorld().rayTraceBlocks(
                eye, dir, length, FluidCollisionMode.NEVER, true);

        // ไม่มีบล็อกบัง = เห็น
        if (hit == null || hit.getHitBlock() == null) return true;

        // บล็อกบังอยู่ใกล้เป้าหมายมาก (เช่น อยู่หลังใบไม้บาง ๆ ขอบ) อนุโลม
        Location hitLoc = hit.getHitPosition().toLocation(viewer.getWorld());
        return hitLoc.distanceSquared(targetLoc) < 2.25; // < 1.5 บล็อกจากตาเป้าหมาย
    }

    private void ensureHidden(Player viewer, Player target, boolean hide) {
        UUID v = viewer.getUniqueId();
        UUID t = target.getUniqueId();
        Set<UUID> set = hidden.computeIfAbsent(v, k -> new HashSet<>());

        if (hide) {
            if (set.add(t)) {
                viewer.hidePlayer(plugin, target);
            }
        } else {
            if (set.remove(t)) {
                viewer.showPlayer(plugin, target);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // ให้ tick ถัดไปจัดการ
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hidden.remove(id);
        for (Set<UUID> set : hidden.values()) set.remove(id);
    }
}
