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

/**
 * A chest where the player drops items and presses the green button.
 * Used by /sell (sell for the worth price) and by /orders (deliver to a buy order).
 */
public final class DepositGui implements Listener {

    private static final int ITEM_SLOTS = 45; // slots 0-44 hold the items
    private static final int BUTTON_FIRST = 48;
    private static final int BUTTON_LAST = 50;

    private enum Mode { SELL, DELIVER }

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PriceManager prices;
    private final OrderBook orders;

    public DepositGui(JavaPlugin plugin, EconomyManager economy, PriceManager prices, OrderBook orders) {
        this.plugin = plugin;
        this.economy = economy;
        this.prices = prices;
        this.orders = orders;
    }

    public void openSell(Player p) {
        open(p, new DepositHolder(Mode.SELL, 0L), "&8ใส่ของที่ต้องการขาย");
    }

    public void openDeliver(Player p, long orderId) {
        OrderBook.Order order = orders.get(orderId);
        if (order == null || order.remaining <= 0) {
            p.sendMessage(Text.c("&cออเดอร์นี้ปิดไปแล้ว"));
            return;
        }
        if (order.owner.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cส่งของให้ออเดอร์ตัวเองไม่ได้"));
            return;
        }
        open(p, new DepositHolder(Mode.DELIVER, orderId), "&8ส่งของ: " + Text.pretty(order.material));
    }

    private void open(Player p, DepositHolder holder, String title) {
        Inventory inv = Bukkit.createInventory(holder, 54, Text.c(title));
        holder.inventory = inv;
        ItemStack filler = Items.named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = ITEM_SLOTS; s < 54; s++) inv.setItem(s, filler);
        refresh(holder);
        p.openInventory(inv);
    }

    /** Redraws the green button with the current total. */
    private void refresh(DepositHolder holder) {
        Inventory inv = holder.inventory;
        ItemStack button;

        if (holder.mode == Mode.SELL) {
            double total = 0;
            for (int i = 0; i < ITEM_SLOTS; i++) {
                ItemStack it = inv.getItem(i);
                if (!Items.isPlain(it)) continue;
                total += prices.sellPrice(it.getType()) * it.getAmount();
            }
            button = Items.named(Material.LIME_STAINED_GLASS_PANE, "&a&lกดเพื่อขาย",
                    "&7ยอดรวม: &e$" + Text.money(total),
                    "&7ของที่ขายไม่ได้จะถูกคืนให้");
        } else {
            OrderBook.Order order = orders.get(holder.orderId);
            if (order == null) {
                button = Items.named(Material.RED_STAINED_GLASS_PANE, "&cออเดอร์ปิดแล้ว");
            } else {
                int have = 0;
                for (int i = 0; i < ITEM_SLOTS; i++) {
                    ItemStack it = inv.getItem(i);
                    if (Items.isPlain(it) && it.getType() == order.material) have += it.getAmount();
                }
                int accepted = Math.min(have, order.remaining);
                button = Items.named(Material.LIME_STAINED_GLASS_PANE, "&a&lกดเพื่อส่งของ",
                        "&7ออเดอร์ต้องการอีก: &f" + order.remaining,
                        "&7จะส่ง: &f" + accepted + " &7ชิ้น",
                        "&7ได้เงิน: &e$" + Text.money(accepted * order.pricePerItem));
            }
        }
        for (int s = BUTTON_FIRST; s <= BUTTON_LAST; s++) inv.setItem(s, button);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof DepositHolder holder)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        // double-click "collect to cursor" could pull the control panes out
        if (e.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            e.setCancelled(true);
            return;
        }

        int raw = e.getRawSlot();
        if (raw >= ITEM_SLOTS && raw < 54) {
            e.setCancelled(true);
            if (raw >= BUTTON_FIRST && raw <= BUTTON_LAST) confirm(p, holder);
            return;
        }
        // placing / taking items is allowed; update the total after the click is applied
        Bukkit.getScheduler().runTask(plugin, () -> refresh(holder));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof DepositHolder holder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw >= ITEM_SLOTS && raw < 54) {
                e.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> refresh(holder));
    }

    /** Whatever is still in the item slots when the chest closes goes back to the player. */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof DepositHolder holder)) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        Inventory inv = holder.inventory;
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) {
                Items.give(p, it.clone());
                inv.setItem(i, null);
            }
        }
    }

    private void confirm(Player p, DepositHolder holder) {
        if (holder.mode == Mode.SELL) confirmSell(p, holder);
        else confirmDeliver(p, holder);
    }

    private void confirmSell(Player p, DepositHolder holder) {
        Inventory inv = holder.inventory;
        double total = 0;
        int count = 0;
        for (int i = 0; i < ITEM_SLOTS; i++) {
            ItemStack it = inv.getItem(i);
            if (!Items.isPlain(it)) continue;
            double price = prices.sellPrice(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
            inv.setItem(i, null);
        }
        if (count == 0) {
            p.sendMessage(Text.c("&cไม่มีของที่ขายได้ในช่องนี้"));
            return;
        }
        economy.add(p.getUniqueId(), total);
        p.sendMessage(Text.c("&aขายของ &f" + count + " &aชิ้น ได้เงิน &e$" + Text.money(total)));
        closeNextTick(p);
    }

    private void confirmDeliver(Player p, DepositHolder holder) {
        OrderBook.Order order = orders.get(holder.orderId);
        if (order == null || order.remaining <= 0) {
            p.sendMessage(Text.c("&cออเดอร์นี้ปิดไปแล้ว ของจะถูกคืนให้"));
            closeNextTick(p);
            return;
        }

        Inventory inv = holder.inventory;
        int need = order.remaining;
        int accepted = 0;
        for (int i = 0; i < ITEM_SLOTS && accepted < need; i++) {
            ItemStack it = inv.getItem(i);
            if (!Items.isPlain(it) || it.getType() != order.material) continue;
            int take = Math.min(it.getAmount(), need - accepted);
            accepted += take;
            if (take == it.getAmount()) {
                inv.setItem(i, null);
            } else {
                it.setAmount(it.getAmount() - take);
                inv.setItem(i, it);
            }
        }
        if (accepted == 0) {
            p.sendMessage(Text.c("&cไม่มีไอเท็มที่ออเดอร์นี้ต้องการในช่อง"));
            return;
        }

        double pay = orders.deliver(order, accepted);
        economy.add(p.getUniqueId(), pay);
        p.sendMessage(Text.c("&aส่ง &f" + Text.pretty(order.material) + " x" + accepted
                + " &aได้เงิน &e$" + Text.money(pay)));

        Player owner = Bukkit.getPlayer(order.owner);
        if (owner != null) {
            owner.sendMessage(Text.c("&a" + p.getName() + " ส่ง &f" + Text.pretty(order.material)
                    + " x" + accepted + " &aให้ออเดอร์ของคุณ &7(ใช้ /orders เพื่อเก็บของ)"));
        }
        closeNextTick(p);
    }

    private void closeNextTick(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
    }

    private static final class DepositHolder implements InventoryHolder {
        final Mode mode;
        final long orderId;
        Inventory inventory;

        DepositHolder(Mode mode, long orderId) {
            this.mode = mode;
            this.orderId = orderId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
