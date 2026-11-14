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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.Map;

/**
 * Orbital Rod: a single-use explosive fishing rod.
 * Casting it uses the rod up. When the hook lands (or after 3 seconds in the air) a ring of TNT falls from the sky
 * around the landing spot, one block at a time, and each one explodes where it hits. The landing spot must be within
 * the rod's range (5, 10, 25 ... blocks). Admins give rods with /orbitalrod <range> [player].
 * Blocks are only broken when orbital.break-blocks is true. Kills count for the player who fired.
 */
public final class OrbitalRod implements Listener, CommandExecutor {

    private static final String ADMIN = "structuresmp.admin";
    private static final String PURPLE = "&#A855F7";
    private static final int MAX_FLIGHT_TICKS = 60;   // 3 seconds in the air at most
    private static final double FALL_SPEED = 1.4;     // blocks per tick

    private final JavaPlugin plugin;
    private final NamespacedKey rangeKey;

    public OrbitalRod(JavaPlugin plugin) {
        this.plugin = plugin;
        this.rangeKey = new NamespacedKey(plugin, "orbital_range");
    }

    public void reload() {
        plugin.getConfig().addDefault("orbital.max-range", 50);
        plugin.getConfig().addDefault("orbital.power", 4.0);
        plugin.getConfig().addDefault("orbital.break-blocks", false);
        plugin.getConfig().addDefault("orbital.tnt-count", 14);
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
                Text.lore("&7ใช้ได้ครั้งเดียว เมื่อคาสต์แล้วเบ็ดจะแตกทันที"),
                Text.lore("&7จุดที่ตกจะถูกถล่มด้วย TNT จากท้องฟ้า"),
                Text.lore("&7ระยะสูงสุด &f" + range + " &7บล็อก"),
                Text.lore(" "),
                Text.lore(PURPLE + "Structure Smp")));
        meta.addEnchant(Enchantment.LURE, 1, true);
        meta.getPersistentDataContainer().set(rangeKey, PersistentDataType.INTEGER, range);
        item.setItemMeta(meta);
        return item;
    }

    private int rangeOf(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) return -1;
        Integer r = item.getItemMeta().getPersistentDataContainer().get(rangeKey, PersistentDataType.INTEGER);
        return r == null ? -1 : r;
    }

    // ------------------------------------------------------------------ cast = use the rod up

    @EventHandler
    public void onCast(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.FISHING) return;
        Player p = e.getPlayer();
        EquipmentSlot slot = null;
        int range = rangeOf(p.getInventory().getItemInMainHand());
        if (range >= 0) {
            slot = EquipmentSlot.HAND;
        } else {
            range = rangeOf(p.getInventory().getItemInOffHand());
            if (range >= 0) slot = EquipmentSlot.OFF_HAND;
        }
        if (range < 0) return;

        // the rod breaks as soon as it is cast
        if (slot == EquipmentSlot.HAND) p.getInventory().setItemInMainHand(null);
        else p.getInventory().setItemInOffHand(null);
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 0.8f);
        p.sendMessage(Text.c(PURPLE + "Orbital Rod แตกแล้ว รอจุดตกของเบ็ด..."));

        FishHook hook = e.getHook();
        final int rangeFinal = range;
        final Player shooter = p;
        final Location[] last = {hook.getLocation().clone()};
        final int[] ticks = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            ticks[0]++;
            if (hook.isValid()) last[0] = hook.getLocation().clone();
            boolean landed = !hook.isValid() || hook.isOnGround() || ticks[0] >= MAX_FLIGHT_TICKS;
            if (!landed) return;
            task[0].cancel();
            if (!shooter.isOnline()) return;

            Location spot = last[0];
            double dist = spot.distance(shooter.getLocation());
            if (dist > rangeFinal) {
                shooter.sendMessage(Text.c("&cจุดตกอยู่ไกลเกินระยะ " + rangeFinal + " บล็อก (" + (int) dist + " บล็อก) ไม่มีการโจมตี"));
                return;
            }
            tntRain(shooter, spot);
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ TNT rain

    /** Rings of TNT fall from the sky onto the spot. Each one explodes where it lands. */
    private void tntRain(Player shooter, Location spot) {
        World w = spot.getWorld();
        if (w == null) return;
        float power = (float) Math.max(0.5, plugin.getConfig().getDouble("orbital.power", 4.0));
        boolean breakBlocks = plugin.getConfig().getBoolean("orbital.break-blocks", false);
        int outer = Math.max(4, plugin.getConfig().getInt("orbital.tnt-count", 14));
        int inner = Math.max(2, outer / 2);

        w.playSound(spot, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 2.0f, 0.8f);
        w.playSound(spot, Sound.ENTITY_WITHER_SPAWN, 0.4f, 1.6f);

        int index = 0;
        for (int i = 0; i < outer; i++) {
            double a = i * (Math.PI * 2 / outer);
            spawnFalling(shooter, spot, a, 6.0, index * 2, power, breakBlocks);
            index++;
        }
        for (int i = 0; i < inner; i++) {
            double a = i * (Math.PI * 2 / inner) + Math.PI / inner;
            spawnFalling(shooter, spot, a, 2.8, index * 2 + 10, power, breakBlocks);
            index++;
        }
    }

    private void spawnFalling(Player shooter, Location spot, double angle, double radius, int delay,
                              float power, boolean breakBlocks) {
        World w = spot.getWorld();
        Location landing = spot.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        Location start = landing.clone().add(0, 30, 0);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            BlockDisplay tnt = w.spawn(start, BlockDisplay.class, d -> {
                d.setBlock(Material.TNT.createBlockData());
                d.setPersistent(false);
            });
            final Location[] pos = {start.clone()};
            final int[] fall = {0};
            final BukkitTask[] task = new BukkitTask[1];
            task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                fall[0]++;
                pos[0].subtract(0, FALL_SPEED, 0);
                if (!tnt.isValid()) {
                    task[0].cancel();
                    return;
                }
                if (pos[0].getY() <= landing.getY() || fall[0] > 60) {
                    task[0].cancel();
                    tnt.remove();
                    if (w.isChunkLoaded(landing.getBlockX() >> 4, landing.getBlockZ() >> 4)) {
                        w.spawnParticle(Particle.EXPLOSION, landing, 1);
                        w.createExplosion(landing, power, false, breakBlocks, shooter);
                    }
                    return;
                }
                tnt.teleport(pos[0]);
            }, 0L, 1L);
        }, delay);
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
