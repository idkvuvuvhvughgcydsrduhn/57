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
import java.util.UUID;

/** /orders - browse buy orders, deliver items to them, collect and cancel your own. */
public final class OrdersGui implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final OrderBook orders;
    private final DepositGui deposit;

    public OrdersGui(JavaPlugin plugin, EconomyManager economy, OrderBook orders, DepositGui deposit) {
        this.plugin = plugin;
        this.economy = economy;
        this.orders = orders;
        this.deposit = deposit;
    }

    public void open(Player p, int page) {
        UUID me = p.getUniqueId();
        List<OrderBook.Order> visible = new ArrayList<>();
        for (OrderBook.Order o : orders.newestFirst()) {
            if (o.remaining > 0 || o.owner.equals(me)) visible.add(o);
        }

        int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        OrdersHolder holder = new OrdersHolder(pg);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.c("&8ออเดอร์รับซื้อ &7(" + (pg + 1) + "/" + pages + ")"));
        holder.inventory = inv;

        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < visible.size(); i++) {
            OrderBook.Order o = visible.get(start + i);
            holder.ids.add(o.id);
            inv.setItem(i, display(o, p));
        }

        ItemStack filler = Items.named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
        if (pg > 0) inv.setItem(45, Items.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, Items.named(Material.ARROW, "&eหน้าถัดไป"));
        inv.setItem(49, Items.named(Material.PAPER, "&e&lออเดอร์รับซื้อ",
                "&7สร้างออเดอร์:",
                "&f/orders create <ไอเท็ม> <จำนวน> <ราคาต่อชิ้น>",
                "&7เช่น &f/orders create diamond 64 700",
                "&7คลิกออเดอร์ของคนอื่นเพื่อส่งของแลกเงิน",
                "&7เงินของคุณ: &a$" + Text.money(economy.get(me))));

        p.openInventory(inv);
    }

    private ItemStack display(OrderBook.Order o, Player viewer) {
        ItemStack item = new ItemStack(o.material);
        item.setAmount(Math.max(1, Math.min(o.remaining, item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        boolean own = o.owner.equals(viewer.getUniqueId());

        List<Component> lore = new ArrayList<>();
        lore.add(Text.lore(own ? "&6ออเดอร์ของคุณ" : "&7ผู้สั่งซื้อ: &f" + o.ownerName));
        lore.add(Text.lore("&7ราคา: &a$" + Text.money(o.pricePerItem) + " &7/ ชิ้น"));
        if (o.remaining > 0) {
            lore.add(Text.lore("&7ต้องการอีก: &f" + o.remaining + " &7/ " + o.total));
        } else {
            lore.add(Text.lore("&aครบจำนวนแล้ว"));
        }
        lore.add(Text.lore(" "));
        if (own) {
            if (o.delivered > 0) lore.add(Text.lore("&7รอเก็บ: &f" + o.delivered + " ชิ้น"));
            lore.add(Text.lore("&eคลิกซ้าย &7เก็บของที่ได้รับ"));
            if (o.remaining > 0) lore.add(Text.lore("&eคลิกขวา &7ยกเลิก (คืนเงินส่วนที่เหลือ)"));
        } else {
            lore.add(Text.lore("&eคลิกซ้าย &7เพื่อส่งของให้ออเดอร์นี้"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof OrdersHolder holder)) return;
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
        if (raw >= PAGE_SIZE || raw >= holder.ids.size()) return;

        long id = holder.ids.get(raw);
        OrderBook.Order o = orders.get(id);
        if (o == null) {
            p.sendMessage(Text.c("&cออเดอร์นี้ปิดไปแล้ว"));
            Bukkit.getScheduler().runTask(plugin, () -> open(p, holder.page));
            return;
        }

        if (!o.owner.equals(p.getUniqueId())) {
            if (e.isLeftClick() && o.remaining > 0) {
                Bukkit.getScheduler().runTask(plugin, () -> deposit.openDeliver(p, id));
            }
            return;
        }

        if (e.isRightClick()) {
            if (o.remaining > 0) {
                double refund = orders.cancel(o);
                economy.add(p.getUniqueId(), refund);
                p.sendMessage(Text.c("&aยกเลิกออเดอร์แล้ว คืนเงิน &e$" + Text.money(refund)));
            }
        } else if (e.isLeftClick()) {
            if (o.delivered <= 0) {
                p.sendMessage(Text.c("&7ยังไม่มีของให้เก็บ"));
            } else {
                Material m = o.material;
                int given = orders.collect(p, o);
                if (given == 0) {
                    p.sendMessage(Text.c("&cกระเป๋าเต็ม เก็บของไม่ได้"));
                } else {
                    p.sendMessage(Text.c("&aเก็บของแล้ว &f" + Text.pretty(m) + " x" + given));
                }
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> open(p, holder.page));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof OrdersHolder) e.setCancelled(true);
    }

    private static final class OrdersHolder implements InventoryHolder {
        final int page;
        final List<Long> ids = new ArrayList<>();
        Inventory inventory;

        OrdersHolder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
