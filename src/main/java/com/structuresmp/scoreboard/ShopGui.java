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

public final class ShopGui implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PriceManager prices;
    private SpecialShopGui special;

    public void setSpecialShop(SpecialShopGui special) {
        this.special = special;
    }

    public ShopGui(JavaPlugin plugin, EconomyManager economy, PriceManager prices) {
        this.plugin = plugin;
        this.economy = economy;
        this.prices = prices;
    }

    public void open(Player p, int page) {
        List<Material> items = prices.buyItems();
        int pages = Math.max(1, (items.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        ShopHolder holder = new ShopHolder(pg);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.c("&8Structure Smp Shop &7(" + (pg + 1) + "/" + pages + ")"));
        holder.inventory = inv;

        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
            inv.setItem(i, shopItem(items.get(start + i)));
        }

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        if (pg > 0) inv.setItem(45, named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, named(Material.ARROW, "&eหน้าถัดไป"));
        inv.setItem(49, balanceItem(p));
        // special shop button, just left of the next-page arrow
        inv.setItem(52, Gui.named(Material.NETHER_STAR, "&#C77DFF&lร้านค้าพิเศษ",
                "&7Netherite Pickaxe 3x3 และกรงสปาวเนอร์", " ", "&eคลิกเพื่อเปิด"));

        p.openInventory(inv);
    }

    private ItemStack shopItem(Material m) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Text.lore("&7ราคา: &a$" + Text.money(prices.buyPrice(m)) + " &7/ ชิ้น"));
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
        return named(Material.GOLD_INGOT,
                "&eเงินของคุณ: &a$" + Text.money(economy.get(p.getUniqueId())));
    }

    private static ItemStack named(Material m, String name) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore(name));
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= 54) return;

        ItemStack clicked = e.getCurrentItem();
        if ((raw == 45 || raw == 53) && clicked != null && clicked.getType() == Material.ARROW) {
            int target = raw == 45 ? holder.page - 1 : holder.page + 1;
            Bukkit.getScheduler().runTask(plugin, () -> open(p, target));
            return;
        }
        if (raw == 52 && special != null) {
            Bukkit.getScheduler().runTask(plugin, () -> special.open(p));
            return;
        }
        if (raw >= PAGE_SIZE || !e.isLeftClick()) return;

        List<Material> items = prices.buyItems();
        int index = holder.page * PAGE_SIZE + raw;
        if (index >= items.size()) return;

        Material picked = items.get(index);
        buy(p, picked, e.isShiftClick() ? Math.min(64, picked.getMaxStackSize()) : 1);
        holder.inventory.setItem(49, balanceItem(p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    private void buy(Player p, Material m, int amount) {
        double unit = prices.buyPrice(m);
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
        p.sendMessage(Text.c("&aซื้อ &f" + Text.pretty(m) + " x" + given
                + " &aราคา &e$" + Text.money(unit * given)));
    }

    private static final class ShopHolder implements InventoryHolder {
        final int page;
        Inventory inventory;

        ShopHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
