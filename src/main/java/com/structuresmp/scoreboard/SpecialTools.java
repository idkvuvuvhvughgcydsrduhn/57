package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Two admin-spawned tools (not sold in the shop):
 *  - Vein Miner Pickaxe (/veinpick): mining one ore breaks the whole connected vein of that ore.
 *  - Timber Axe (/timberaxe): chopping one log fells the whole tree.
 * Sneak to break a single block.
 */
public final class SpecialTools implements Listener, CommandExecutor {

    private static final String PURPLE = "&#A855F7";

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final Set<UUID> breaking = new HashSet<>(); // players whose extra blocks are being broken right now

    public SpecialTools(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "special_tool");
    }

    public void reload() {
        plugin.getConfig().addDefault("tools.vein-max-blocks", 64);
        plugin.getConfig().addDefault("tools.timber-max-blocks", 128);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------ items

    public ItemStack createTool(String type) {
        boolean vein = type.equals("vein");
        ItemStack item = new ItemStack(vein ? Material.NETHERITE_PICKAXE : Material.NETHERITE_AXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore(vein ? "&#C77DFF&lVein Miner Pickaxe" : "&#C77DFF&lTimber Axe"));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.lore(vein
                ? "&7ขุดแร่ครั้งเดียว ได้ทั้งเส้นที่ติดกัน"
                : "&7ตัดไม้ครั้งเดียว ต้นไม้ล้มทั้งต้น"));
        lore.add(Text.lore("&8กด Shift ค้างไว้เพื่อขุดทีละบล็อก"));
        lore.add(Text.lore(" "));
        lore.add(Text.lore(PURPLE + "Structure Smp"));
        meta.lore(lore);
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        if (vein) meta.addEnchant(Enchantment.FORTUNE, 3, true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type);
        item.setItemMeta(meta);
        return item;
    }

    private String typeOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ block groups

    private static boolean isOre(Material m) {
        return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
    }

    /** IRON_ORE and DEEPSLATE_IRON_ORE belong to the same vein. */
    private static String oreFamily(Material m) {
        String n = m.name();
        return n.startsWith("DEEPSLATE_") ? n.substring("DEEPSLATE_".length()) : n;
    }

    /** OAK_LOG, OAK_WOOD, STRIPPED_OAK_LOG ... are all "OAK". */
    private static String species(Material m) {
        return m.name().replace("STRIPPED_", "").replaceAll("_(LOG|WOOD|STEM|HYPHAE)$", "");
    }

    /** Connected blocks (26 neighbours) that match, starting from {@code start} (which comes first). */
    private static List<Block> flood(Block start, Predicate<Block> match, int limit) {
        List<Block> out = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty() && out.size() < limit) {
            Block b = queue.poll();
            out.add(b);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = b.getRelative(dx, dy, dz);
                        if (seen.contains(n)) continue;
                        if (!n.getWorld().isChunkLoaded(n.getX() >> 4, n.getZ() >> 4)) continue;
                        if (!match.test(n)) continue;
                        seen.add(n);
                        queue.add(n);
                    }
                }
            }
        }
        return out;
    }

    /** A real tree has leaves touching its logs (so log cabins are left alone). */
    private static boolean touchesLeaves(List<Block> logs) {
        for (Block b : logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (Tag.LEAVES.isTagged(b.getRelative(dx, dy, dz).getType())) return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ breaking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (breaking.contains(p.getUniqueId()) || p.isSneaking()) return;
        String type = typeOf(p.getInventory().getItemInMainHand());
        if (type == null) return;

        Block start = e.getBlock();
        List<Block> blocks;
        if (type.equals("vein")) {
            if (!isOre(start.getType())) return;
            String family = oreFamily(start.getType());
            int limit = Math.max(1, plugin.getConfig().getInt("tools.vein-max-blocks", 64));
            blocks = flood(start, b -> isOre(b.getType()) && oreFamily(b.getType()).equals(family), limit);
        } else if (type.equals("timber")) {
            if (!Tag.LOGS.isTagged(start.getType())) return;
            String wood = species(start.getType());
            int limit = Math.max(1, plugin.getConfig().getInt("tools.timber-max-blocks", 128));
            blocks = flood(start, b -> Tag.LOGS.isTagged(b.getType()) && species(b.getType()).equals(wood), limit);
            if (!touchesLeaves(blocks)) return; // not a tree: break just this log
        } else {
            return;
        }

        blocks.remove(0); // the block being broken right now
        if (blocks.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> breakAll(p, blocks, type));
    }

    private void breakAll(Player p, List<Block> blocks, String type) {
        if (!p.isOnline()) return;
        UUID id = p.getUniqueId();
        breaking.add(id);
        try {
            for (Block b : blocks) {
                ItemStack tool = p.getInventory().getItemInMainHand();
                if (!type.equals(typeOf(tool))) break; // the tool broke or was swapped
                Material m = b.getType();
                boolean ok = type.equals("vein") ? isOre(m) : Tag.LOGS.isTagged(m);
                if (!ok || !b.isPreferredTool(tool)) continue;
                p.breakBlock(b); // fires BlockBreakEvent (protection plugins work), drops, XP and tool damage
            }
        } finally {
            breaking.remove(id);
        }
    }

    // ------------------------------------------------------------------ /veinpick /timberaxe

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("structuresmp.admin")) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return true;
        }
        String type = cmd.getName().equalsIgnoreCase("veinpick") ? "vein" : "timber";
        String name = type.equals("vein") ? "Vein Miner Pickaxe" : "Timber Axe";

        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Text.c("&cไม่พบผู้เล่นที่ออนไลน์ชื่อ " + args[0]));
                return true;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            sender.sendMessage("Usage: /" + label.toLowerCase(Locale.ROOT) + " <player>");
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createTool(type));
        for (ItemStack rest : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        sender.sendMessage(Text.c(PURPLE + "ให้ " + name + " แก่ " + target.getName() + " แล้ว"));
        if (!target.equals(sender)) {
            target.sendMessage(Text.c(PURPLE + "คุณได้รับ " + name));
        }
        return true;
    }
}
