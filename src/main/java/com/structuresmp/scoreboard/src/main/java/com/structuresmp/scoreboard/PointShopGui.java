package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /pointshop - buy items and enchanted books with points.
 *  Main menu  -> Items (page list)  or  Enchantments (page list) -> pick an enchantment -> pick a level.
 * Prices (in points) are in config.yml under "pointshop".
 */
public final class PointShopGui implements Listener, CommandExecutor {

    private static final int PAGE_SIZE = 45;
    private static final String PURPLE = "&#A855F7";

    private enum Screen { MAIN, ITEMS, ENCHANTS, LEVELS }

    private final JavaPlugin plugin;
    private final PointsManager points;
    private final Map<Material, Long> itemPrices = new LinkedHashMap<>();
    private final List<Enchantment> enchants = new ArrayList<>();
    private long enchantBase = 20;
    private double enchantMult = 2.5;

    public PointShopGui(JavaPlugin plugin, PointsManager points) {
        this.plugin = plugin;
        this.points = points;
    }

    public void reload() {
        plugin.getConfig().addDefault("pointshop.enchant-base-price", 20);
        plugin.getConfig().addDefault("pointshop.enchant-level-multiplier", 2.5);
        plugin.getConfig().addDefault("pointshop.items.GOLDEN_APPLE", 50);
        plugin.getConfig().addDefault("pointshop.items.ENCHANTED_GOLDEN_APPLE", 500);
        plugin.getConfig().addDefault("pointshop.items.ENDER_PEARL", 20);
        plugin.getConfig().addDefault("pointshop.items.TOTEM_OF_UNDYING", 400);
        plugin.getConfig().addDefault("pointshop.items.ELYTRA", 3000);
        plugin.getConfig().addDefault("pointshop.items.EXPERIENCE_BOTTLE", 10);
        plugin.getConfig().addDefault("pointshop.items.DIAMOND", 15);
        plugin.getConfig().addDefault("pointshop.items.NETHERITE_INGOT", 1000);
        plugin.getConfig().addDefault("pointshop.items.EMERALD", 5);
        plugin.getConfig().addDefault("pointshop.items.IRON_INGOT", 3);
        plugin.getConfig().addDefault("pointshop.items.GOLD_INGOT", 4);
        plugin.getConfig().addDefault("pointshop.items.BLAZE_ROD", 8);
        plugin.getConfig().addDefault("pointshop.items.SHULKER_SHELL", 200);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        enchantBase = Math.max(1, cfg.getLong("pointshop.enchant-base-price", 20));
        enchantMult = Math.max(1.0, cfg.getDouble("pointshop.enchant-level-multiplier", 2.5));

        itemPrices.clear();
        var section = cfg.getConfigurationSection("pointshop.items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                long price = section.getLong(key, 0);
                if (m != null && m.isItem() && price > 0) itemPrices.put(m, price);
            }
        }

        enchants.clear();
        for (Enchantment e : Registry.ENCHANTMENT) {
            if (!e.isCursed()) enchants.add(e); // curses cannot be sold
        }
        enchants.sort((a, b) -> prettyEnchant(a).compareTo(prettyEnchant(b)));
    }

    // ------------------------------------------------------------------ helpers

    private long levelPrice(int level) {
        return Math.round(enchantBase * Math.pow(enchantMult, level - 1));
    }

    private static String prettyEnchant(Enchantment e) {
        StringBuilder sb = new StringBuilder();
        for (String w : e.getKey().getKey().split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
    }

    private static ItemStack named(Material m, String name, String... lore) {
        return Gui.named(m, name, lore);
    }

    private static ItemStack enchantBook(Enchantment e, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta instanceof EnchantmentStorageMeta esm) {
            esm.addStoredEnchant(e, level, true);
            esm.displayName(Text.lore("&#C77DFF" + prettyEnchant(e) + " " + roman(level)));
            book.setItemMeta(esm);
        }
        return book;
    }

    private Component balanceLine(Player p) {
        return Text.lore(PURPLE + "พ้อยของคุณ: &f" + PointsManager.format(points.get(p.getUniqueId())));
    }

    // ------------------------------------------------------------------ open screens

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        open(p, Screen.MAIN, 0, null);
        return true;
    }

    private void open(Player p, Screen screen, int page, Enchantment ench) {
        PsHolder holder = new PsHolder(screen, page, ench);
        Inventory inv;
        switch (screen) {
            case MAIN -> {
                inv = Bukkit.createInventory(holder, 27, Text.c("&8ร้านค้าพ้อย"));
                holder.inventory = inv;
                ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
                for (int i = 0; i < 27; i++) inv.setItem(i, pane);
                inv.setItem(11, named(Material.CHEST, "&#C77DFFไอเท็ม",
                        "&7ไอเท็มและวัสดุต่างๆ", " ", "&eคลิกเพื่อเปิด"));
                inv.setItem(15, named(Material.ENCHANTED_BOOK, "&#C77DFFเอนชานต์",
                        "&7หนังสือเอนชานต์ทุกชนิดและทุกระดับ", " ", "&eคลิกเพื่อเปิด"));
                inv.setItem(22, named(Material.NETHER_STAR, "&#A855F7พ้อยของคุณ",
                        "&f" + PointsManager.format(points.get(p.getUniqueId())) + " พ้อย"));
            }
            case ITEMS -> {
                List<Map.Entry<Material, Long>> list = new ArrayList<>(itemPrices.entrySet());
                int pages = Gui.pages(list.size(), PAGE_SIZE);
                int pg = Math.max(0, Math.min(page, pages - 1));
                holder.page = pg;
                inv = Bukkit.createInventory(holder, 54, Text.c("&8ร้านพ้อย: ไอเท็ม &7(" + (pg + 1) + "/" + pages + ")"));
                holder.inventory = inv;
                int start = pg * PAGE_SIZE;
                for (int i = 0; i < PAGE_SIZE && start + i < list.size(); i++) {
                    Map.Entry<Material, Long> e = list.get(start + i);
                    holder.items[i] = e.getKey();
                    inv.setItem(i, named(e.getKey(), "&f" + Text.pretty(e.getKey()),
                            "&7ราคา: &d" + PointsManager.format(e.getValue()) + " พ้อย",
                            " ", "&eคลิกซ้ายเพื่อซื้อ 1 ชิ้น", "&eShift + คลิก &7ซื้อ 16 ชิ้น"));
                }
                bottom(inv, pg, pages, p);
            }
            case ENCHANTS -> {
                int pages = Gui.pages(enchants.size(), PAGE_SIZE);
                int pg = Math.max(0, Math.min(page, pages - 1));
                holder.page = pg;
                inv = Bukkit.createInventory(holder, 54, Text.c("&8ร้านพ้อย: เอนชานต์ &7(" + (pg + 1) + "/" + pages + ")"));
                holder.inventory = inv;
                int start = pg * PAGE_SIZE;
                for (int i = 0; i < PAGE_SIZE && start + i < enchants.size(); i++) {
                    Enchantment e = enchants.get(start + i);
                    holder.enchants[i] = e;
                    inv.setItem(i, named(Material.ENCHANTED_BOOK, "&#C77DFF" + prettyEnchant(e),
                            "&7ระดับสูงสุด: &f" + roman(e.getMaxLevel()),
                            "&7ระดับ 1: &d" + PointsManager.format(levelPrice(1)) + " พ้อย",
                            " ", "&eคลิกเพื่อเลือกระดับ"));
                }
                bottom(inv, pg, pages, p);
            }
            case LEVELS -> {
                inv = Bukkit.createInventory(holder, 27, Text.c("&8" + prettyEnchant(ench)));
                holder.inventory = inv;
                ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
                for (int i = 0; i < 27; i++) inv.setItem(i, pane);
                int max = ench.getMaxLevel();
                for (int lvl = 1; lvl <= max && lvl <= 7; lvl++) {
                    int slot = 9 + lvl; // slots 10..16 for levels 1..7
                    ItemStack book = enchantBook(ench, lvl);
                    ItemMeta m = book.getItemMeta();
                    m.lore(List.of(Text.lore("&7ราคา: &d" + PointsManager.format(levelPrice(lvl)) + " พ้อย"),
                            Text.lore(" "), Text.lore("&eคลิกซ้ายเพื่อซื้อ")));
                    book.setItemMeta(m);
                    holder.levels[slot] = lvl;
                    inv.setItem(slot, book);
                }
                inv.setItem(22, named(Material.BARRIER, "&eกลับ"));
                inv.setItem(18, named(Material.NETHER_STAR, "&#A855F7พ้อยของคุณ",
                        "&f" + PointsManager.format(points.get(p.getUniqueId()))));
            }
            default -> {
                return;
            }
        }
        p.openInventory(inv);
    }

    private void bottom(Inventory inv, int pg, int pages, Player p) {
        Gui.bottomRow(inv);
        if (pg > 0) inv.setItem(45, Gui.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, Gui.named(Material.ARROW, "&eหน้าถัดไป"));
        inv.setItem(49, Gui.named(Material.BARRIER, "&eกลับ"));
        inv.setItem(47, Gui.named(Material.NETHER_STAR, "&#A855F7พ้อยของคุณ",
                "&f" + PointsManager.format(points.get(p.getUniqueId()))));
    }

    // ------------------------------------------------------------------ buying

    private boolean charge(Player p, long price) {
        long have = points.get(p.getUniqueId());
        if (have < price) {
            p.sendMessage(Text.c("&cพ้อยไม่พอ ต้องใช้ &d" + PointsManager.format(price) + " &cพ้อย (คุณมี "
                    + PointsManager.format(have) + ")"));
            return false;
        }
        points.set(p.getUniqueId(), have - price);
        points.save();
        return true;
    }

    private void buyItem(Player p, Material m, int amount, long unit) {
        long total = unit * amount;
        ItemStack stack = new ItemStack(m, amount);
        if (!Gui.canFit(p.getInventory(), stack)) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        if (!charge(p, total)) return;
        p.getInventory().addItem(stack);
        p.sendMessage(Text.c(PURPLE + "ซื้อ &f" + Text.pretty(m) + " x" + amount + " &7ด้วย &d"
                + PointsManager.format(total) + " พ้อย"));
    }

    private void buyEnchant(Player p, Enchantment e, int level) {
        ItemStack book = enchantBook(e, level);
        if (!Gui.canFit(p.getInventory(), book)) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        long price = levelPrice(level);
        if (!charge(p, price)) return;
        p.getInventory().addItem(book);
        p.sendMessage(Text.c(PURPLE + "ซื้อ &f" + prettyEnchant(e) + " " + roman(level) + " &7ด้วย &d"
                + PointsManager.format(price) + " พ้อย"));
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof PsHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getInventory().getSize()) return;
        ItemStack clicked = e.getCurrentItem();

        switch (holder.screen) {
            case MAIN -> {
                if (raw == 11) Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.ITEMS, 0, null));
                else if (raw == 15) Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.ENCHANTS, 0, null));
            }
            case ITEMS -> {
                if ((raw == 45 || raw == 53) && Gui.isArrow(clicked)) {
                    int target = raw == 45 ? holder.page - 1 : holder.page + 1;
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.ITEMS, target, null));
                    return;
                }
                if (raw == 49) {
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.MAIN, 0, null));
                    return;
                }
                if (raw >= PAGE_SIZE || holder.items[raw] == null || !e.isLeftClick()) return;
                Material m = holder.items[raw];
                long unit = itemPrices.getOrDefault(m, 0L);
                if (unit <= 0) return;
                buyItem(p, m, e.isShiftClick() ? 16 : 1, unit);
                Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.ITEMS, holder.page, null));
            }
            case ENCHANTS -> {
                if ((raw == 45 || raw == 53) && Gui.isArrow(clicked)) {
                    int target = raw == 45 ? holder.page - 1 : holder.page + 1;
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.ENCHANTS, target, null));
                    return;
                }
                if (raw == 49) {
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.MAIN, 0, null));
                    return;
                }
                if (raw >= PAGE_SIZE || holder.enchants[raw] == null || !e.isLeftClick()) return;
                Enchantment ench = holder.enchants[raw];
                Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.LEVELS, 0, ench));
            }
            case LEVELS -> {
                if (raw == 22) {
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.ENCHANTS, 0, null));
                    return;
                }
                if (raw >= 27 || holder.levels[raw] == 0 || !e.isLeftClick()) return;
                buyEnchant(p, holder.enchantTarget, holder.levels[raw]);
                Bukkit.getScheduler().runTask(plugin, () -> open(p, Screen.LEVELS, 0, holder.enchantTarget));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof PsHolder) e.setCancelled(true);
    }

    private static final class PsHolder implements InventoryHolder {
        final Screen screen;
        int page;
        final Enchantment enchantTarget;
        final Material[] items = new Material[PAGE_SIZE];
        final Enchantment[] enchants = new Enchantment[PAGE_SIZE];
        final int[] levels = new int[27];
        Inventory inventory;

        PsHolder(Screen screen, int page, Enchantment enchantTarget) {
            this.screen = screen;
            this.page = page;
            this.enchantTarget = enchantTarget;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
