package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orbital Rod: an explosive fishing rod. Cast it, and when you reel in, the hook spot is struck after
 * one second. The rod has a range (5, 10, 15 ... up to orbital.max-range blocks): the hook must land
 * within that range of you. Admins give rods with /orbitalrod <range> [player].
 * Blocks are not broken unless orbital.break-blocks is true. Kills count for the player who struck.
 */
public final class OrbitalRod implements Listener, CommandExecutor {

    private static final String ADMIN = "structuresmp.admin";
    private static final String PURPLE = "&#A855F7";

    private final JavaPlugin plugin;
    private final NamespacedKey rangeKey;
    private final Map<UUID, Long> cooldown = new HashMap<>();

    public OrbitalRod(JavaPlugin plugin) {
        this.plugin = plugin;
        this.rangeKey = new NamespacedKey(plugin, "orbital_range");
    }

    public void reload() {
        plugin.getConfig().addDefault("orbital.max-range", 50);
        plugin.getConfig().addDefault("orbital.power", 4.0);
        plugin.getConfig().addDefault("orbital.break-blocks", false);
        plugin.getConfig().addDefault("orbital.cooldown-seconds", 3);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    private int maxRange() {
        return Math.max(5, plugin.getConfig().getInt("orbital.max-range", 50));
    }

    // ------------------------------------------------------------------ item

    public ItemStack createRod(int range) {
        ItemStack item = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore("&#C77DFFOrbital Rod &7(ระยะ " + range + " บล็อก)"));
        meta.lore(List.of(
                Text.lore("&7คาสต์แล้วรอจุดตกของเบ็ด"),
                Text.lore("&7ตอนดึงเบ็ด จุดนั้นจะถูกโจมตีด้วยการระเบิด"),
                Text.lore("&7ระยะสูงสุด &f" + range + " &7บล็อก"),
                Text.lore(" "),
                Text.lore(PURPLE + "Structure Smp")));
        meta.addEnchant(Enchantment.LURE, 1, true);
        meta.getPersistentDataContainer().set(rangeKey, PersistentDataType.INTEGER, range);
        item.setItemMeta(meta);
        return item;
    }

    /** Range of the orbital rod in this hand, or -1 when the item is not an orbital rod. */
    private int rangeOf(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return -1;
        Integer r = item.getItemMeta().getPersistentDataContainer().get(rangeKey, PersistentDataType.INTEGER);
        return r == null ? -1 : r;
    }

    // ------------------------------------------------------------------ strike

    @EventHandler
    public void onReel(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.REEL_IN) return;
        Player p = e.getPlayer();
        int range = rangeOf(p.getInventory().getItemInMainHand());
        if (range < 0) range = rangeOf(p.getInventory().getItemInOffHand());
        if (range < 0) return;

        Location spot = e.getHook().getLocation().clone();
        double dist = spot.distance(p.getLocation());
        if (dist > range) {
            p.sendMessage(Text.c("&cจุดตกอยู่ไกลเกินระยะ " + range + " บล็อก (" + (int) dist + " บล็อก)"));
            return;
        }

        long now = System.currentTimeMillis();
        long cd = Math.max(0, plugin.getConfig().getLong("orbital.cooldown-seconds", 3)) * 1000L;
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            p.sendMessage(Text.c("&cรออีก " + ((cd - (now - last)) / 1000 + 1) + " วินาที ก่อนยิงอีกครั้ง"));
            return;
        }
        cooldown.put(p.getUniqueId(), now);

        p.sendMessage(Text.c(PURPLE + "เรียกการโจมตีที่ " + (int) spot.getX() + ", " + (int) spot.getY()
                + ", " + (int) spot.getZ()));
        strike(p, spot);
    }

    /** Beam from the sky, then the explosion after one second. */
    private void strike(Player shooter, Location spot) {
        World w = spot.getWorld();
        if (w == null) return;
        float power = (float) Math.max(0.5, plugin.getConfig().getDouble("orbital.power", 4.0));
        boolean breakBlocks = plugin.getConfig().getBoolean("orbital.break-blocks", false);
        Location target = spot.clone();
        Location sky = spot.clone().add(0, 30, 0);

        w.playSound(sky, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.8f);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (int y = 0; y < 30; y++) {
                w.spawnParticle(Particle.END_ROD, target.clone().add(0, y, 0), 2, 0.1, 0.1, 0.1, 0.0);
            }
            w.spawnParticle(Particle.EXPLOSION_EMITTER, target, 1);
        }, 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!w.isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)) return;
            w.createExplosion(target, power, false, breakBlocks, shooter);
        }, 20L);
    }

    // ------------------------------------------------------------------ /orbitalrod

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return true;
        }
        int max = maxRange();
        if (args.length < 1) {
            sender.sendMessage(Text.c("&eใช้: &f/orbitalrod <ระยะ> [ผู้เล่น] &7(5, 10, 15 ... สูงสุด " + max + ")"));
            return true;
        }
        int range;
        try {
            range = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            range = -1;
        }
        if (range < 5 || range > max || range % 5 != 0) {
            sender.sendMessage(Text.c("&cระยะต้องเป็นทวีคูณของ 5 ตั้งแต่ 5 ถึง " + max));
            return true;
        }
        Player target;
        if (args.length > 1) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Text.c("&cไม่พบผู้เล่นที่ออนไลน์ชื่อ " + args[1]));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Usage: /orbitalrod <range> <player>");
            return true;
        }
        Map<Integer, ItemStack> left = target.getInventory().addItem(createRod(range));
        for (ItemStack rest : left.values()) target.getWorld().dropItemNaturally(target.getLocation(), rest);
        sender.sendMessage(Text.c(PURPLE + "ให้ Orbital Rod ระยะ " + range + " แก่ " + target.getName()));
        if (!target.equals(sender)) target.sendMessage(Text.c(PURPLE + "คุณได้รับ Orbital Rod ระยะ " + range));
        return true;
    }
}
