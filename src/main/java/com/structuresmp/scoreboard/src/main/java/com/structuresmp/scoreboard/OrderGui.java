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

/**
 * /orders menu. Players post buy orders (money is held until filled);
 * other players click an order to sell their items to it.
 */
public final class OrderGui implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final OrderManager orders;

    public OrderGui(JavaPlugin plugin, EconomyManager economy, OrderManager orders) {
        this.plugin = plugin;
        this.economy = economy;
        this.orders = orders;
    }

    public void open(Player p, int page, boolean mine) {
        UUID me = p.getUniqueId();
        List<OrderManager.Order> list = mine ? orders.byOwner(me) : orders.open(me);
        int pages = Gui.pages(list.size(), PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        OrdHolder holder = new OrdHolder(mine, pg);
        String title = (mine ? "&8ออเดอร์ของฉัน" : "&8Orders") + " &7(" + (pg + 1) + "/" + pages + ")";
        Inventory inv = Bukkit.createInventory(holder, 54, Text.c(title));
        holder.inventory = inv;

        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < list.size(); i++) {
            OrderManager.Order o = list.get(start + i);
            holder.ids[i] = o.id;
            inv.setItem(i, display(o, mine));
        }

        Gui.bottomRow(inv);
        if (pg > 0) inv.setItem(45, Gui.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, Gui.named(Material.ARROW, "&eหน้าถัดไป"));
        if (mine) {
            inv.setItem(49, Gui.named(Material.BARRIER, "&eกลับไปหน้าออเดอร์"));
        } else {
            inv.setItem(49, Gui.named(Material.CHEST, "&eออเดอร์ของฉัน",
                    "&7รับของที่ส่งมา หรือยกเลิกออเดอร์"));
            inv.setItem(47, Gui.named(Material.BOOK, "&eวิธีสร้างออเดอร์",
                    "&f/orders create <ไอเท็ม> <จำนวน> <ราคาต่อชิ้น>",
                    "&7เช่น &f/orders create diamond 64 500",
                    "&7ไอเท็มเป็น &fhand &7ได้ (ใช้ชนิดที่ถืออยู่)"));
        }
        p.openInventory(inv);
    }

    private ItemStack display(OrderManager.Order o, boolean mine) {
        ItemStack item = new ItemStack(o.material);
        int shown = Math.max(1, Math.min(item.getMaxStackSize(), mine ? o.amount : o.remaining()));
        item.setAmount(shown);
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (mine) {
            lore.add(Text.lore("&7ส่งมาแล้ว: &f" + o.filled + "/" + o.amount));
            lore.add(Text.lore("&7ราคา/ชิ้น: &a$" + Text.money(o.price)));
            lore.add(Text.lore(" "));
            if (o.pending() > 0) {
                lore.add(Text.lore("&aมีของรอรับ: &f" + o.pending() + " ชิ้น"));
                lore.add(Text.lore("&eคลิกซ้าย &7รับของ"));
            }
            if (o.remaining() > 0) {
                lore.add(Text.lore("&eShift + คลิก &7ยกเลิก คืนเงิน $" + Text.money(o.remaining() * o.price)));
            }
        } else {
            lore.add(Text.lore("&7ต้องการอีก: &f" + o.remaining() + "/" + o.amount));
            lore.add(Text.lore("&7ราคา/ชิ้น: &a$" + Text.money(o.price)));
            lore.add(Text.lore("&7ผู้สั่ง: &f" + o.ownerName));
            lore.add(Text.lore(" "));
            lore.add(Text.lore("&eคลิกซ้าย &7ส่งของจากกระเป๋า รับเงินทันที"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** /orders create <item|hand> <amount> <priceEach> */
    public void create(Player p, String materialArg, String amountArg, String priceArg) {
        Material m = materialArg.equalsIgnoreCase("hand")
                ? p.getInventory().getItemInMainHand().getType()
                : Material.matchMaterial(materialArg);
        if (m == null || m.isAir() || !m.isItem()) {
            p.sendMessage(Text.c("&cไม่พบไอเท็มนี้ (ลองพิมพ์ชื่อภาษาอังกฤษ เช่น diamond หรือถือของแล้วใช้ hand)"));
            return;
        }
        int maxAmount = plugin.getConfig().getInt("orders.max-amount", 2304);
        int amount;
        try {
            amount = Integer.parseInt(amountArg);
        } catch (NumberFormatException ex) {
            amount = -1;
        }
        if (amount < 1 || amount > maxAmount) {
            p.sendMessage(Text.c("&cจำนวนต้องอยู่ระหว่าง 1 ถึง " + maxAmount));
            return;
        }
        double price = Text.parseAmount(priceArg);
        double maxPrice = plugin.getConfig().getDouble("ah.max-price", 1_000_000_000d);
        if (price <= 0 || price > maxPrice) {
            p.sendMessage(Text.c("&cราคาต่อชิ้นไม่ถูกต้อง"));
            return;
        }
        UUID id = p.getUniqueId();
        int maxOrders = plugin.getConfig().getInt("orders.max-orders", 5);
        if (orders.countByOwner(id) >= maxOrders) {
            p.sendMessage(Text.c("&cสร้างออเดอร์ได้สูงสุด " + maxOrders + " รายการ"));
            return;
        }
        double total = price * amount;
        if (!economy.take(id, total)) {
            p.sendMessage(Text.c("&cเงินไม่พอ ต้องใช้ &a$" + Text.money(total)));
            return;
        }
        orders.create(id, p.getName(), m, amount, price);
        economy.save();
        p.sendMessage(Text.c("&aสร้างออเดอร์ &f" + Text.pretty(m) + " x" + amount
                + " &aราคาชิ้นละ &e$" + Text.money(price) + " &7(หักเงินไว้ $" + Text.money(total) + ")"));
    }

    private void fill(Player p, int id) {
        OrderManager.Order o = orders.find(id);
        if (o == null || o.remaining() <= 0) {
            p.sendMessage(Text.c("&cออเดอร์นี้ครบแล้วหรือถูกยกเลิกไปแล้ว"));
            return;
        }
        if (o.owner.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cส่งของให้ออเดอร์ตัวเองไม่ได้"));
            return;
        }
        int have = Gui.countPlain(p.getInventory(), o.material);
        int qty = Math.min(o.remaining(), have);
        if (qty <= 0) {
            p.sendMessage(Text.c("&cคุณไม่มี " + Text.pretty(o.material) + " ในกระเป๋า"));
            return;
        }
        Gui.removePlain(p.getInventory(), o.material, qty);
        orders.fill(o, qty);
        double pay = qty * o.price;
        economy.add(p.getUniqueId(), pay);
        economy.save();

        p.sendMessage(Text.c("&aส่ง &f" + Text.pretty(o.material) + " x" + qty + " &aได้เงิน &e$" + Text.money(pay)));
        Player owner = Bukkit.getPlayer(o.owner);
        if (owner != null) {
            owner.sendMessage(Text.c("&a" + p.getName() + " ส่ง &f" + Text.pretty(o.material) + " x" + qty
                    + " &aให้ออเดอร์ของคุณ &7(พิมพ์ /orders mine เพื่อรับของ)"));
        }
    }

    private void collect(Player p, int id) {
        OrderManager.Order o = orders.find(id);
        if (o == null || !o.owner.equals(p.getUniqueId())) return;
        if (o.pending() <= 0) {
            p.sendMessage(Text.c("&7ยังไม่มีของให้รับ"));
            return;
        }
        int given = Gui.give(p.getInventory(), o.material, o.pending());
        if (given <= 0) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        orders.collect(o, given);
        p.sendMessage(Text.c("&aรับ &f" + Text.pretty(o.material) + " x" + given));
    }

    private void cancel(Player p, int id) {
        OrderManager.Order o = orders.find(id);
        if (o == null || !o.owner.equals(p.getUniqueId())) return;
        if (o.remaining() <= 0) {
            p.sendMessage(Text.c("&7ออเดอร์นี้ครบแล้ว ไม่มีส่วนที่ยกเลิกได้"));
            return;
        }
        double refund = o.remaining() * o.price;
        orders.cancel(o);
        economy.add(p.getUniqueId(), refund);
        economy.save();
        p.sendMessage(Text.c("&aยกเลิกออเดอร์แล้ว คืนเงิน &e$" + Text.money(refund)));
    }

    private void reopen(Player p, int page, boolean mine) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, page, mine));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof OrdHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= 54) return;

        ItemStack clicked = e.getCurrentItem();
        if ((raw == 45 || raw == 53) && Gui.isArrow(clicked)) {
            reopen(p, raw == 45 ? holder.page - 1 : holder.page + 1, holder.mine);
            return;
        }
        if (raw == 49) {
            reopen(p, 0, !holder.mine);
            return;
        }
        if (raw >= PAGE_SIZE) return;

        int id = holder.ids[raw];
        if (id == 0) return;
        if (holder.mine) {
            if (e.isShiftClick()) cancel(p, id);
            else if (e.isLeftClick()) collect(p, id);
            else return;
        } else {
            if (!e.isLeftClick()) return;
            fill(p, id);
        }
        reopen(p, holder.page, holder.mine);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof OrdHolder) e.setCancelled(true);
    }

    private static final class OrdHolder implements InventoryHolder {
        final boolean mine;
        final int page;
        final int[] ids = new int[PAGE_SIZE];
        Inventory inventory;

        OrdHolder(boolean mine, int page) {
            this.mine = mine;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
