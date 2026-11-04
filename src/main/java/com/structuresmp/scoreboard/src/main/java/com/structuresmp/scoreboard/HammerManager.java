package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Hammer 3x3: a netherite pickaxe with a custom 3D model (resource pack) that breaks a 3x3 area,
 * one block deep, around the block you mine. Sneak to mine a single block.
 * /hammer [player] gives one (OP). /hammer pack re-sends the resource pack.
 */
public final class HammerManager implements Listener, CommandExecutor {

    private static final int MODEL_ID = 7001; // must match the threshold in the resource pack
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("structuresmp-hammer-pack".getBytes());
    private static final String PURPLE = "&#A855F7";

    // resource pack v2 = 64x64 netherite pickaxe model (file StructureHammerPack2.zip in the repo root)
    private static final int PACK_VERSION = 2;
    private static final String OLD_PACK_URL =
            "https://github.com/idkvuvuvhvughgcydsrduhn/57/raw/main/StructureHammerPack.zip";
    private static final String PACK_URL =
            "https://github.com/idkvuvuvhvughgcydsrduhn/57/raw/main/StructureHammerPack2.zip";
    private static final String PACK_SHA1 = "5b7d9869a5931312c2b4a981d69d968e17fc85a9";

    private static final Set<Material> NEVER_BREAK = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.SPAWNER, Material.TRIAL_SPAWNER, Material.VAULT,
            Material.REINFORCED_DEEPSLATE, Material.END_PORTAL_FRAME, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.STRUCTURE_BLOCK,
            Material.JIGSAW);

    private final JavaPlugin plugin;
    private final NamespacedKey key;
    private final Set<UUID> breaking = new HashSet<>(); // players whose extra blocks are being broken right now

    private boolean packEnabled;
    private String packUrl = "";
    private byte[] packHash;
    private boolean packRequired;

    public HammerManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "hammer");
    }

    public void reload() {
        // new pack version: refresh the stored hash once (and the url, unless it was customised)
        var cfg = plugin.getConfig();
        if (cfg.getInt("hammer.resource-pack.version", 1) < PACK_VERSION) {
            String url = cfg.getString("hammer.resource-pack.url", "");
            if (url == null || url.isBlank() || url.equals(OLD_PACK_URL)) {
                cfg.set("hammer.resource-pack.url", PACK_URL);
            }
            cfg.set("hammer.resource-pack.sha1", PACK_SHA1);
            cfg.set("hammer.resource-pack.version", PACK_VERSION);
        }

        plugin.getConfig().addDefault("hammer.resource-pack.enabled", true);
        plugin.getConfig().addDefault("hammer.resource-pack.url", PACK_URL);
        plugin.getConfig().addDefault("hammer.resource-pack.sha1", PACK_SHA1);
        plugin.getConfig().addDefault("hammer.resource-pack.required", false);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        packEnabled = plugin.getConfig().getBoolean("hammer.resource-pack.enabled", true);
        packUrl = plugin.getConfig().getString("hammer.resource-pack.url", "");
        packHash = parseHash(plugin.getConfig().getString("hammer.resource-pack.sha1", ""));
        packRequired = plugin.getConfig().getBoolean("hammer.resource-pack.required", false);
    }

    private static byte[] parseHash(String hex) {
        if (hex == null || hex.trim().length() != 40) return null;
        String h = hex.trim();
        byte[] out = new byte[20];
        try {
            for (int i = 0; i < 20; i++) {
                out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    // ---------------------------------------------------------------- item

    public ItemStack createHammer() {
        ItemStack item = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore("&#C77DFF&lNetherite Pickaxe 3x3"));
        List<Component> lore = new ArrayList<>();
        lore.add(Text.lore("&7ขุดครั้งเดียวได้ 3x3 บล็อก (ลึก 1 บล็อก)"));
        lore.add(Text.lore("&8กด Shift ค้างไว้เพื่อขุดทีละบล็อก"));
        lore.add(Text.lore(" "));
        lore.add(Text.lore(PURPLE + "Structure Smp"));
        meta.lore(lore);
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addEnchant(Enchantment.MENDING, 1, true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);

        CustomModelDataComponent model = meta.getCustomModelDataComponent();
        model.setFloats(List.of((float) MODEL_ID));
        meta.setCustomModelDataComponent(model);

        item.setItemMeta(meta);
        return item;
    }

    private boolean isHammer(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ---------------------------------------------------------------- 3x3 mining

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (breaking.contains(p.getUniqueId())) return; // this is one of our own extra breaks
        if (p.isSneaking()) return;                     // sneak = single block
        if (!isHammer(p.getInventory().getItemInMainHand())) return;

        Block center = e.getBlock();
        BlockFace face = faceOf(p, center);

        List<Block> extra = new ArrayList<>();
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                switch (face) {
                    case UP, DOWN -> extra.add(center.getRelative(a, 0, b));
                    case NORTH, SOUTH -> extra.add(center.getRelative(a, b, 0));
                    default -> extra.add(center.getRelative(0, b, a));
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> breakAll(p, extra));
    }

    /** Which side of the block the player is mining (decides the direction of the 3x3 square). */
    private BlockFace faceOf(Player p, Block center) {
        RayTraceResult hit = p.rayTraceBlocks(7.0, FluidCollisionMode.NEVER);
        if (hit != null && center.equals(hit.getHitBlock()) && hit.getHitBlockFace() != null) {
            return hit.getHitBlockFace();
        }
        float pitch = p.getLocation().getPitch();
        if (pitch > 50) return BlockFace.UP;
        if (pitch < -50) return BlockFace.DOWN;
        return p.getFacing().getOppositeFace();
    }

    private void breakAll(Player p, List<Block> blocks) {
        if (!p.isOnline()) return;
        UUID id = p.getUniqueId();
        breaking.add(id);
        try {
            for (Block b : blocks) {
                ItemStack tool = p.getInventory().getItemInMainHand();
                if (!isHammer(tool)) break; // the hammer broke or was swapped
                if (!canBreak(b, tool)) continue;
                p.breakBlock(b); // fires BlockBreakEvent (protection plugins work), drops, XP and tool damage
            }
        } finally {
            breaking.remove(id);
        }
    }

    private static boolean canBreak(Block b, ItemStack tool) {
        Material type = b.getType();
        if (type.isAir() || b.isLiquid() || NEVER_BREAK.contains(type)) return false;
        if (type.getHardness() < 0) return false;
        return b.isPreferredTool(tool); // only blocks a pickaxe is made for (stone, ores, ...)
    }

    // ---------------------------------------------------------------- resource pack

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> sendPack(p), 40L);
    }

    private void sendPack(Player p) {
        if (!packEnabled || packUrl == null || packUrl.isBlank() || !p.isOnline()) return;
        p.setResourcePack(PACK_ID, packUrl, packHash,
                Text.c(PURPLE + "Structure Smp Resource Pack (โมเดล Hammer 3x3)"), packRequired);
    }

    // ---------------------------------------------------------------- /hammer

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("pack")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            if (!packEnabled || packUrl == null || packUrl.isBlank()) {
                p.sendMessage(Text.c("&cยังไม่ได้ตั้งค่า Resource Pack"));
                return true;
            }
            sendPack(p);
            p.sendMessage(Text.c(PURPLE + "ส่ง Resource Pack ให้แล้ว กด Accept ในหน้าต่างที่ขึ้นมา"));
            return true;
        }

        if (!sender.hasPermission("structuresmp.admin")) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return true;
        }

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
            sender.sendMessage("Usage: /hammer <player>");
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createHammer());
        for (ItemStack rest : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), rest);
        }
        sender.sendMessage(Text.c(PURPLE + "ให้ Hammer 3x3 แก่ " + target.getName() + " แล้ว"));
        if (!target.equals(sender)) {
            target.sendMessage(Text.c(PURPLE + "คุณได้รับ Hammer 3x3"));
        }
        return true;
    }
}
