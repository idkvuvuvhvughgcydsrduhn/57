package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sell Axe: left-click a chest, trapped chest, barrel or shulker box with it to sell the sellable items inside.
 * Only boxes the player placed can be sold from (protects other players' storage), unless the player is OP.
 * Ender chests are never sold from. /sellaxe [player] gives the axe (OP).
 * Owners are saved in plugins/StructureScoreboard/sellaxe-boxes.yml
 */
public final class SellAxe implements Listener, CommandExecutor {

    private static final String PURPLE = "&#A855F7";
    private static final String ADMIN = "structuresmp.admin";

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PriceManager prices;
    private final NamespacedKey key;
    private final File file;
    private final Map<String, UUID> owners = new HashMap<>();

    public SellAxe(JavaPlugin plugin, EconomyManager economy, PriceManager prices) {
        this.plugin = plugin;
        this.economy = economy;
        this.prices = prices;
        this.key = new NamespacedKey(plugin, "sell_axe");
        this.file = new File(plugin.getDataFolder(), "sellaxe-boxes.yml");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("boxes");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                owners.put(k.replace('|', ';'), UUID.fromString(s.getString(k, "")));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, UUID> e : owners.entrySet()) {
            yml.set("boxes." + e.getKey().replace(';', '|'), e.getValue().toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save sellaxe-boxes.yml: " + ex.getMessage());
        }
    }

    private static String keyOf(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private static boolean isBox(Material m) {
        String n = m.name();
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL || n.endsWith("SHULKER_BOX");
    }

    // ------------------------------------------------------------------ item

    public ItemStack createAxe() {
        ItemStack item = new ItemStack(Material.NETHERITE_AXE);
        var meta = item.getItemMeta();
        meta.displayName(Text.lore("&#C77DFFSell Axe"));
        meta.lore(java.util.List.of(
                Text.lore("&7คลิกซ้ายที่กล่องหรือชัลเกอร์บ็อกซ์ที่คุณวาง"),
                Text.lore("&7เพื่อขายของที่ขายได้ในกล่องนั้น"),
                Text.lore(" "),
                Text.lore(PURPLE + "Structure Smp")));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isAxe(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ owners

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isBox(b.getType())) return;
        owners.put(keyOf(b), e.getPlayer().getUniqueId());
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (owners.remove(keyOf(b)) != null) save();
    }

    // ------------------------------------------------------------------ selling

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getHand() == null) return;
        Player p = e.getPlayer();
        if (!isAxe(p.getInventory().getItemInMainHand())) return;

        Block b = e.getClickedBlock();
        if (b == null || !isBox(b.getType())) return;
        e.setCancelled(true); // do not break the box

        boolean admin = p.hasPermission(ADMIN);
        UUID owner = owners.get(keyOf(b));
        if (!admin && (owner == null || !owner.equals(p.getUniqueId()))) {
            p.sendMessage(Text.c("&cขายได้เฉพาะกล่องที่คุณเป็นคนวาง"));
            return;
        }
        if (!(b.getState() instanceof Container c)) return;

        Inventory inv = c.getInventory();
        ItemStack[] contents = inv.getContents();
        double total = 0;
        long count = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            if (ShulkerSell.isShulker(it)) { // shulkers inside the box: sell their contents too
                ShulkerSell.Sold s = ShulkerSell.sell(it, prices);
                total += s.total();
                count += s.count();
                contents[i] = it;
                continue;
            }
            double price = prices.sellPrice(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
            contents[i] = null;
        }
        if (count == 0) {
            p.sendMessage(Text.c("&cไม่มีของที่ขายได้ในกล่องนี้"));
            return;
        }
        inv.setContents(contents);
        c.update();
        economy.add(p.getUniqueId(), Math.round(total * 100.0) / 100.0);
        economy.save();
        p.sendMessage(Text.c(PURPLE + "ขายของในกล่อง &f" + count + " &7ชิ้น ได้เงิน &e$" + Text.money(total)));
    }

    // ------------------------------------------------------------------ /sellaxe

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
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
            sender.sendMessage("Usage: /sellaxe <player>");
            return true;
        }
        Map<Integer, ItemStack> left = target.getInventory().addItem(createAxe());
        for (ItemStack rest : left.values()) target.getWorld().dropItemNaturally(target.getLocation(), rest);
        sender.sendMessage(Text.c(PURPLE + "ให้ Sell Axe แก่ " + target.getName()));
        if (!target.equals(sender)) target.sendMessage(Text.c(PURPLE + "คุณได้รับ Sell Axe"));
        return true;
    }
}
