package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /shop: a category menu (PvP, Ores, Building, Food, Other), then the items of that category.
 * Armour is never sold here. Prices come from PriceManager (prices.yml).
 */
public final class ShopGui implements Listener {

    public enum Cat {
        PVP("หมวด PvP", Material.IRON_SWORD),
        ORE("หมวดแร่", Material.DIAMOND),
        BUILD("หมวดก่อสร้าง", Material.BRICKS),
        FOOD("หมวดอาหาร", Material.COOKED_BEEF),
        MISC("หมวดวัสดุอื่น", Material.CHEST);

        final String label;
        final Material icon;

        Cat(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private static final int PAGE_SIZE = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_SPECIAL = 52;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;
    private static final int SLOT_BALANCE = 46;
    private static final Map<Cat, Integer> MENU_SLOTS = Map.of(
            Cat.PVP, 10, Cat.ORE, 12, Cat.BUILD, 14, Cat.FOOD, 16, Cat.MISC, 22);
    private static final int MENU_SPECIAL = 26;

    private static final java.util.Set<Material> PVP_ITEMS = java.util.Set.of(
            Material.BOW, Material.CROSSBOW, Material.ARROW, Material.SHIELD, Material.TRIDENT, Material.MACE,
            Material.TOTEM_OF_UNDYING, Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE,
            Material.ENDER_PEARL, Material.BLAZE_ROD);
    private static final java.util.Set<Material> ORE_ITEMS = java.util.Set.of(
            Material.COAL, Material.CHARCOAL, Material.DIAMOND, Material.EMERALD, Material.REDSTONE,
            Material.LAPIS_LAZULI, Material.QUARTZ, Material.NETHERITE_SCRAP, Material.GOLD_NUGGET,
            Material.AMETHYST_SHARD, Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.COPPER_BLOCK,
            Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK, Material.COAL_BLOCK, Material.LAPIS_BLOCK,
            Material.REDSTONE_BLOCK, Material.NETHERITE_BLOCK);

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PriceManager prices;
    private SpecialShopGui special;

    public ShopGui(JavaPlugin plugin, EconomyManager economy, PriceManager prices) {
        this.plugin = plugin;
        this.economy = economy;
        this.prices = prices;
    }

    public void setSpecialShop(SpecialShopGui special) {
        this.special = special;
    }

    // ------------------------------------------------------------------ categories

    /** Armour is not sold in the shop. */
    static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS")
                || n.endsWith("_BOOTS");
    }

    /** Category of a shop item, or null when the item is not shown in the shop. */
    static Cat classify(Material m) {
        if (isArmor(m)) return null;
        String n = m.name();
        if (PVP_ITEMS.contains(m) || n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR")) return Cat.PVP;
        if (ORE_ITEMS.contains(m) || n.startsWith("RAW_") || n.endsWith("_ORE") || n.endsWith("_INGOT")) return Cat.ORE;
        if (m.isEdible()) return Cat.FOOD;
        if (m.isBlock() || m == Material.BUCKET) return Cat.BUILD;
        return Cat.MISC;
    }

    private List<Material> itemsOf(Cat cat) {
        List<Material> out = new ArrayList<>();
        for (Material m : prices.buyItems()) {
            if (classify(m) == cat) out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ screens

    /** Called by /shop: opens the category menu. */
    public void open(Player p, int page) {
        openMenu(p);
    }

    private void openMenu(Player p) {
        ShopHolder holder = new ShopHolder(true, null, 0);
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&8Structure Smp Shop"));
        holder.inventory = inv;
        ItemStack pane = Gui.named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        for (Map.Entry<Cat, Integer> e : MENU_SLOTS.entrySet()) {
            Cat c = e.getKey();
            inv.setItem(e.getValue(), Gui.named(c.icon, "&#C77DFF" + c.label,
                    "&7จำนวน: &f" + itemsOf(c).size() + " อย่าง", " ", "&eคลิกเพื่อเปิด"));
        }
        inv.setItem(4, balanceItem(p));
        inv.setItem(MENU_SPECIAL, Gui.named(Material.NETHER_STAR, "&#C77DFFร้านค้าพิเศษ",
                "&7Netherite Pickaxe 3x3 และกรงสปาวเนอร์", " ", "&eคลิกเพื่อเปิด"));
        p.openInventory(inv);
    }

    private void openCategory(Player p, Cat cat, int page) {
        List<Material> items = itemsOf(cat);
        int pages = Gui.pages(items.size(), PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        ShopHolder holder = new ShopHolder(false, cat, pg);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.c("&8" + cat.label + " &7(" + (pg + 1) + "/" + pages + ")"));
        holder.inventory = inv;

        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
            Material m = items.get(start + i);
            holder.items[i] = m;
            inv.setItem(i, shopItem(m));
        }

        Gui.bottomRow(inv);
        if (pg > 0) inv.setItem(SLOT_PREV, Gui.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(SLOT_NEXT, Gui.named(Material.ARROW, "&eหน้าถัดไป"));
        inv.setItem(SLOT_BALANCE, balanceItem(p));
        inv.setItem(SLOT_BACK, Gui.named(Material.BARRIER, "&eกลับไปหมวดหมู่"));
        inv.setItem(SLOT_SPECIAL, Gui.named(Material.NETHER_STAR, "&#C77DFFร้านค้าพิเศษ",
                "&7Netherite Pickaxe 3x3 และกรงสปาวเนอร์", " ", "&eคลิกเพื่อเปิด"));
        p.openInventory(inv);
    }

    /** Shop price = prices.yml buy price x shop.buy-multiplier (default 3). Sell prices are not changed. */
    private double shopPrice(Material m) {
        double mult = plugin.getConfig().getDouble("shop.buy-multiplier", 3.0);
        return prices.buyPrice(m) * Math.max(0, mult);
    }

    private ItemStack shopItem(Material m) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Text.lore("&7ราคา: &a$" + Text.money(shopPrice(m)) + " &7/ ชิ้น"));
        double sell = prices.sellPrice(m);
        if (sell > 0) lore.add(Text.lore("&7ขายคืนได้: &e$" + Text.money(sell) + " &7/ ชิ้น"));
        lore.add(Text.lore(" "));
        lore.add(Text.lore("&eคลิกซ้าย &7ซื้อ 1 ชิ้น"));
        int stack = Math.min(64, m.getMaxStackSize());
        if (stack > 1) lore.add(Text.lore("&eShift + คลิกซ้าย &7ซื้อ " + stack + " ชิ้น"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack balanceItem(Player p) {
        return Gui.named(Material.GOLD_INGOT, "&eเงินของคุณ: &a$" + Text.money(economy.get(p.getUniqueId())));
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getInventory().getSize()) return;

        if (holder.menu) {
            if (raw == MENU_SPECIAL && special != null) {
                Bukkit.getScheduler().runTask(plugin, () -> special.open(p));
                return;
            }
            for (Map.Entry<Cat, Integer> entry : MENU_SLOTS.entrySet()) {
                if (entry.getValue() == raw) {
                    Cat cat = entry.getKey();
                    Bukkit.getScheduler().runTask(plugin, () -> openCategory(p, cat, 0));
                    return;
                }
            }
            return;
        }

        if (raw == SLOT_PREV || raw == SLOT_NEXT) {
            if (Gui.isArrow(e.getCurrentItem())) {
                int target = raw == SLOT_PREV ? holder.page - 1 : holder.page + 1;
                Bukkit.getScheduler().runTask(plugin, () -> openCategory(p, holder.cat, target));
            }
            return;
        }
        if (raw == SLOT_BACK) {
            Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
            return;
        }
        if (raw == SLOT_SPECIAL && special != null) {
            Bukkit.getScheduler().runTask(plugin, () -> special.open(p));
            return;
        }
        if (raw >= PAGE_SIZE || holder.items[raw] == null || !e.isLeftClick()) return;

        Material picked = holder.items[raw];
        buy(p, picked, e.isShiftClick() ? Math.min(64, picked.getMaxStackSize()) : 1);
        holder.inventory.setItem(SLOT_BALANCE, balanceItem(p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    private void buy(Player p, Material m, int amount) {
        if (classify(m) == null) {
            p.sendMessage(Text.c("&cชุดเกราะไม่มีขายในร้านค้า"));
            return;
        }
        double unit = shopPrice(m);
        if (unit <= 0) return;
        UUID id = p.getUniqueId();
        double total = unit * amount;

        if (!economy.take(id, total)) {
            p.sendMessage(Text.c("&cเงินไม่พอ ต้องใช้ &a$" + Text.money(total)));
            return;
        }

        Map<Integer, ItemStack> leftover = p.getInventory().addItem(new ItemStack(m, amount));
        int notGiven = 0;
        for (ItemStack it : leftover.values()) notGiven += it.getAmount();
        if (notGiven > 0) economy.add(id, unit * notGiven);

        int given = amount - notGiven;
        if (given <= 0) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม ซื้อไม่ได้"));
            return;
        }
        prices.recordBought(m, given);
        p.sendMessage(Text.c("&aซื้อ &f" + Text.pretty(m) + " x" + given
                + " &aราคา &e$" + Text.money(unit * given)));
    }

    private static final class ShopHolder implements InventoryHolder {
        final boolean menu;
        final Cat cat;
        final int page;
        final Material[] items = new Material[PAGE_SIZE];
        Inventory inventory;

        ShopHolder(boolean menu, Cat cat, int page) {
            this.menu = menu;
            this.cat = cat;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
