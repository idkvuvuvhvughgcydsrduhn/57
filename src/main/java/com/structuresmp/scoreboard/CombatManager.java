package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Combat tag. A player who takes damage caused by another PLAYER, by any means (melee with any
 * item or fist, arrows, tridents, TNT, end crystals, respawn anchors, beds, harmful potions),
 * is tagged for combat.seconds. The attacker is tagged too (combat.tag-attacker).
 * Mobs do not tag anyone unless combat.tag-from-mobs is true.
 * The timer is shown as small purple text on the action bar.
 */
public final class CombatManager implements Listener {

    private static final String PURPLE = "&#A855F7";
    private static final String LIGHT_PURPLE = "&#D8B4FE";

    private record CrystalHit(UUID player, long time) {
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Long> combat = new HashMap<>();            // player -> combat ends at (ms)
    private final Map<UUID, CrystalHit> crystals = new HashMap<>();    // end crystal -> player who hit it
    private Set<String> blocked = new HashSet<>();
    private BukkitTask task;

    // last respawn anchor / bed a player clicked (they explode in the wrong dimension)
    private UUID blastPlayer;
    private Location blastLoc;
    private long blastTime;

    public CombatManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        // make sure the newer options exist in config.yml
        plugin.getConfig().addDefault("combat.tag-attacker", true);
        plugin.getConfig().addDefault("combat.tag-from-mobs", false);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

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

    /** Small purple text on the action bar. */
    private void showTimer(Player p, long seconds) {
        p.sendActionBar(Text.c(PURPLE + "Combat " + LIGHT_PURPLE + seconds + "s"));
    }

    private void tag(Player p) {
        combat.put(p.getUniqueId(), System.currentTimeMillis() + seconds() * 1000L);
        showTimer(p, seconds());
    }

    private void tagPair(Player attacker, Player victim) {
        if (attacker == null || victim == null || attacker.equals(victim)) return;
        tag(victim);
        if (plugin.getConfig().getBoolean("combat.tag-attacker", true)) tag(attacker);
    }

    /** Once a second: update the countdown and release players whose time is up. */
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
                p.sendActionBar(Text.c(PURPLE + "ออกจาก Combat แล้ว"));
            } else {
                showTimer(p, (left + 999) / 1000);
            }
        }

        crystals.values().removeIf(hit -> now - hit.time() > 15_000L);
    }

    /** The player behind a damage source: the player, the shooter of an arrow/trident/etc., or who lit the TNT. */
    private static Player playerFrom(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    /** True when the damage comes from a mob (or a mob's arrow). */
    private static boolean fromMob(Entity damager) {
        if (damager instanceof Player) return false;
        if (damager instanceof LivingEntity) return true;
        return damager instanceof Projectile proj
                && proj.getShooter() instanceof LivingEntity shooter
                && !(shooter instanceof Player);
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
            attacker = playerFrom(damager);
        }

        if (attacker != null) {
            tagPair(attacker, target);
        } else if (fromMob(damager) && plugin.getConfig().getBoolean("combat.tag-from-mobs", false)) {
            tag(target);
        }
    }

    /** Harmful splash potions (instant damage, poison, wither) thrown by a player. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!enabled()) return;
        if (!(e.getPotion().getShooter() instanceof Player thrower)) return;

        boolean harmful = false;
        for (PotionEffect fx : e.getPotion().getEffects()) {
            PotionEffectType type = fx.getType();
            if (type.equals(PotionEffectType.INSTANT_DAMAGE)
                    || type.equals(PotionEffectType.POISON)
                    || type.equals(PotionEffectType.WITHER)) {
                harmful = true;
            }
        }
        if (!harmful) return;

        for (LivingEntity hit : e.getAffectedEntities()) {
            if (hit instanceof Player target && e.getIntensity(hit) > 0) {
                tagPair(thrower, target);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean anchor = b.getType() == Material.RESPAWN_ANCHOR;
        boolean bed = Tag.BEDS.isTagged(b.getType()) && b.getWorld().getEnvironment() != World.Environment.NORMAL;
        if (!anchor && !bed) return;
        blastPlayer = e.getPlayer().getUniqueId();
        blastLoc = b.getLocation().add(0.5, 0.5, 0.5);
        blastTime = System.currentTimeMillis();
    }

    /** Anchor and bed explosions: damage right after a player clicked one close by. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplosion(EntityDamageEvent e) {
        if (!enabled() || e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;
        if (!(e.getEntity() instanceof Player target) || blastPlayer == null || blastLoc == null) return;
        if (System.currentTimeMillis() - blastTime > 1500L) return;
        if (!target.getWorld().equals(blastLoc.getWorld())) return;
        if (target.getLocation().distanceSquared(blastLoc) > 100) return;

        tagPair(Bukkit.getPlayer(blastPlayer), target);
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
            p.sendMessage(Text.c(PURPLE + "ใช้คำสั่งนี้ไม่ได้ขณะ Combat (เหลืออีก " + secondsLeft(p) + " วินาที)"));
        }
    }
}
