package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/** /sell menu: put items in the top slots, press the green button to sell. */
public final class SellGui implements Listener {

    private static final int SLOTS = 45;   // slots 0-44 hold the items
    private static final int BUTTON = 49;  // green sell button

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PriceManager prices;

    public SellGui(JavaPlugin plugin, EconomyManager economy, PriceManager prices) {
        this.plugin = plugin;
        this.economy = economy;
        this.prices = prices;
    }

    public void open(Player p) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Text.c("&8ขายของ - วางของแล้วกดปุ่มสีเขียว"));
        holder.inventory = inv;
        Gui.bottomRow(inv);
        refresh(inv);
        p.openInventory(inv);
    }

    /** Closes any open sell menu (items are returned by the close handler). */
    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) {
                p.closeInventory();
            }
        }
    }

    private void refresh(Inventory inv) {
        double total = 0;
        int count = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            double price = prices.sellPrice(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
        }
        inv.setItem(BUTTON, Gui.named(Material.LIME_STAINED_GLASS_PANE, "&a&lขายเลย",
                "&7ของที่ขายได้: &f" + count + " ชิ้น",
                "&7ได้เงินรวม: &a$" + Text.money(total),
                " ",
                "&eคลิกเพื่อขาย"));
    }

    private void sell(Player p, Inventory inv) {
        double total = 0;
        int count = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            double price = prices.sellPrice(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }
        if (count == 0) {
            p.sendMessage(Text.c("&cไม่มีของที่ขายได้ในช่อง"));
            return;
        }
        economy.add(p.getUniqueId(), total);
        p.sendMessage(Text.c("&aขายของ &f" + count + " &aชิ้น ได้เงิน &e$" + Text.money(total)));
        refresh(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SellHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        int raw = e.getRawSlot();
        if (raw >= SLOTS && raw < 54) { // bottom row of the menu: buttons only
            e.setCancelled(true);
            if (raw == BUTTON) sell(p, holder.inventory);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> refresh(holder.inventory));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof SellHolder holder)) return;
        for (int slot : e.getRawSlots()) {
            if (slot >= SLOTS && slot < 54) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> refresh(holder.inventory));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof SellHolder holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack it = holder.inventory.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            holder.inventory.setItem(i, null);
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack rest : left.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);
            }
        }
    }

    private static final class SellHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
