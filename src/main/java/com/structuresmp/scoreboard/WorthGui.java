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

/** /worth menu: every sellable item, most expensive first (view only). */
public final class WorthGui implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final PriceManager prices;

    public WorthGui(JavaPlugin plugin, PriceManager prices) {
        this.plugin = plugin;
        this.prices = prices;
    }

    public void open(Player p, int page) {
        List<Material> items = prices.sellItems();
        int pages = Gui.pages(items.size(), PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        WorthHolder holder = new WorthHolder(pg);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.c("&8Worth - ของที่ขายได้ &7(" + (pg + 1) + "/" + pages + ")"));
        holder.inventory = inv;

        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < items.size(); i++) {
            inv.setItem(i, display(items.get(start + i), start + i + 1));
        }

        Gui.bottomRow(inv);
        if (pg > 0) inv.setItem(45, Gui.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, Gui.named(Material.ARROW, "&eหน้าถัดไป"));
        inv.setItem(49, Gui.named(Material.BOOK, "&eราคารับซื้อ (แพงสุดไปถูกสุด)",
                "&7ขายได้ทั้งหมด &f" + items.size() + " &7อย่าง",
                "&7ขายของ: &f/sell",
                "&7ดูราคาของในมือ: &f/worth hand",
                "&8ขายได้เฉพาะของที่ไม่มีชื่อ/เอนชานต์"));
        p.openInventory(inv);
    }

    private ItemStack display(Material m, int rank) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        lore.add(Text.lore("&7ราคารับซื้อ: &a$" + Text.money(prices.sellPrice(m)) + " &7/ ชิ้น"));
        double buy = prices.buyPrice(m);
        if (buy > 0) lore.add(Text.lore("&7ซื้อได้ที่ร้าน: &e$" + Text.money(buy)));
        lore.add(Text.lore("&8อันดับ #" + rank));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof WorthHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if ((raw == 45 || raw == 53) && Gui.isArrow(e.getCurrentItem())) {
            int target = raw == 45 ? holder.page - 1 : holder.page + 1;
            Bukkit.getScheduler().runTask(plugin, () -> open(p, target));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof WorthHolder) e.setCancelled(true);
    }

    private static final class WorthHolder implements InventoryHolder {
        final int page;
        Inventory inventory;

        WorthHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
