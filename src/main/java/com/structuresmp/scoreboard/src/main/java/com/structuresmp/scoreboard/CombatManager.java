package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PvP combat tag. A player hit by another PLAYER (any melee item or fist, arrow/trident,
 * end crystal, respawn anchor) is tagged for combat.seconds. Mobs never tag anyone.
 * Both the attacker and the victim are tagged.
 */
public final class CombatManager implements Listener {

    private record CrystalHit(UUID player, long time) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Long> combat = new HashMap<>();            // player -> combat ends at (ms)
    private final Map<UUID, CrystalHit> crystals = new HashMap<>();    // end crystal -> player who hit it
    private Set<String> blocked = new HashSet<>();
    private BukkitTask task;

    // last respawn anchor a player right-clicked (anchors explode when used outside the Nether)
    private UUID anchorPlayer;
    private Location anchorLoc;
    private long anchorTime;

    public CombatManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        blocked = new HashSet<>();
        for (String s : plugin.getConfig().getStringList("combat.blocked-commands")) {
            blocked.add(s.toLowerCase(Locale.ROOT));
        }
        if (task != null) task.cancel();
        if (enabled()) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        } else {
            task = null;
            combat.clear();
        }
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("combat.enabled", true);
    }

    private int seconds() {
        return Math.max(1, plugin.getConfig().getInt("combat.seconds", 20));
    }

    public boolean isInCombat(Player p) {
        Long until = combat.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    private long secondsLeft(Player p) {
        Long until = combat.get(p.getUniqueId());
        if (until == null) return 0;
        return Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
    }

    private void tag(Player p) {
        boolean first = !isInCombat(p);
        combat.put(p.getUniqueId(), System.currentTimeMillis() + seconds() * 1000L);
        if (first) {
            p.sendMessage(Text.c("&c&l[COMBAT] &cคุณเข้าสู่โหมด Combat " + seconds()
                    + " วินาที &7(ออกเกมตอนนี้ = ตาย)"));
        }
    }

    /** Once a second: show the countdown and release players whose time is up. */
    private void tick() {
        long now = System.currentTimeMillis();

        Iterator<Map.Entry<UUID, Long>> it = combat.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            long left = e.getValue() - now;
            if (left <= 0) {
                it.remove();
                p.sendActionBar(Text.c("&a&l[COMBAT] &aออกจากโหมด Combat แล้ว"));
                p.sendMessage(Text.c("&aคุณออกจากโหมด Combat แล้ว"));
            } else {
                p.sendActionBar(Text.c("&c&l[COMBAT] &f" + ((left + 999) / 1000) + " วินาที"));
            }
        }

        crystals.values().removeIf(hit -> now - hit.time() > 15_000L);
    }

    /** The player behind a damage source: the player himself, or the shooter of an arrow/trident. */
    private static Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!enabled()) return;
        Entity victim = e.getEntity();
        Entity damager = e.getDamager();

        // remember who set off an end crystal (hit, shot, or chain explosion)
        if (victim instanceof EnderCrystal) {
            UUID owner = null;
            Player byPlayer = playerFrom(damager);
            if (byPlayer != null) {
                owner = byPlayer.getUniqueId();
            } else if (damager instanceof EnderCrystal other) {
                CrystalHit hit = crystals.get(other.getUniqueId());
                if (hit != null) owner = hit.player();
            }
            if (owner != null) {
                crystals.put(victim.getUniqueId(), new CrystalHit(owner, System.currentTimeMillis()));
            }
            return;
        }

        if (!(victim instanceof Player target)) return;

        Player attacker;
        if (damager instanceof EnderCrystal crystal) {
            CrystalHit hit = crystals.get(crystal.getUniqueId());
            attacker = hit == null ? null : Bukkit.getPlayer(hit.player());
        } else {
            attacker = playerFrom(damager); // mobs (and mob arrows) give null
        }
        if (attacker == null || attacker.equals(target)) return;

        tag(attacker);
        tag(target);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.RESPAWN_ANCHOR) return;
        anchorPlayer = e.getPlayer().getUniqueId();
        anchorLoc = b.getLocation().add(0.5, 0.5, 0.5);
        anchorTime = System.currentTimeMillis();
    }

    /** Respawn anchor explosions: damage right after a player clicked an anchor close by. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(EntityDamageEvent e) {
        if (!enabled() || e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (!(e.getEntity() instanceof Player target) || anchorPlayer == null || anchorLoc == null) return;
        if (System.currentTimeMillis() - anchorTime > 1500L) return;
        if (!target.getWorld().equals(anchorLoc.getWorld())) return;
        if (target.getLocation().distanceSquared(anchorLoc) > 100) return;

        Player attacker = Bukkit.getPlayer(anchorPlayer);
        if (attacker == null || attacker.equals(target)) return;
        tag(attacker);
        tag(target);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        combat.remove(dead.getUniqueId());
        Player killer = dead.getKiller();
        if (killer != null) combat.remove(killer.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        boolean wasInCombat = isInCombat(p);
        combat.remove(p.getUniqueId());
        if (!wasInCombat || !enabled()) return;
        if (!plugin.getConfig().getBoolean("combat.kill-on-quit", true)) return;
        if (Bukkit.isStopping()) return; // do not punish players when the server shuts down

        p.setHealth(0.0); // combat logging: the player dies and drops their items
        Bukkit.broadcast(Text.c("&c" + p.getName() + " ออกเกมระหว่าง Combat และตาย!"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!enabled() || !isInCombat(p)) return;

        String label = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (blocked.contains(label)) {
            e.setCancelled(true);
            p.sendMessage(Text.c("&cใช้คำสั่งนี้ไม่ได้ขณะ Combat (เหลืออีก " + secondsLeft(p) + " วินาที)"));
        }
    }
}
