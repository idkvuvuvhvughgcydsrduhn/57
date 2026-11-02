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

/** /ah menu: browse and buy listings, and manage your own. */
public final class AuctionGui implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final AuctionManager auctions;

    public AuctionGui(JavaPlugin plugin, EconomyManager economy, AuctionManager auctions) {
        this.plugin = plugin;
        this.economy = economy;
        this.auctions = auctions;
    }

    public void open(Player p, int page, boolean mine) {
        long now = System.currentTimeMillis();
        List<AuctionManager.Listing> list = mine ? auctions.byOwner(p.getUniqueId()) : auctions.active(now);
        int pages = Gui.pages(list.size(), PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        AhHolder holder = new AhHolder(mine, pg);
        String title = (mine ? "&8รายการของฉัน" : "&8Auction House") + " &7(" + (pg + 1) + "/" + pages + ")";
        Inventory inv = Bukkit.createInventory(holder, 54, Text.c(title));
        holder.inventory = inv;

        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < list.size(); i++) {
            AuctionManager.Listing l = list.get(start + i);
            holder.ids[i] = l.id;
            inv.setItem(i, display(l, mine, now));
        }

        Gui.bottomRow(inv);
        if (pg > 0) inv.setItem(45, Gui.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, Gui.named(Material.ARROW, "&eหน้าถัดไป"));
        if (mine) {
            inv.setItem(49, Gui.named(Material.BARRIER, "&eกลับไปตลาด"));
        } else {
            inv.setItem(49, Gui.named(Material.CHEST, "&eรายการของฉัน",
                    "&7ดู ยกเลิก หรือรับของคืน"));
            inv.setItem(47, Gui.named(Material.BOOK, "&eวิธีลงขาย",
                    "&7ถือของไว้ในมือ แล้วพิมพ์",
                    "&f/ah sell <ราคา>",
                    "&7เช่น &f/ah sell 5000 &7หรือ &f/ah sell 2.5k"));
        }
        p.openInventory(inv);
    }

    /** Fee taken from the seller when a listing sells (percent, from config). */
    private double fee() {
        return Math.max(0, Math.min(100, plugin.getConfig().getDouble("ah.tax-percent", 5.0)));
    }

    /** What the seller receives after the fee. */
    private double net(double price) {
        return Math.round(price * (100 - fee())) / 100.0;
    }

    private ItemStack display(AuctionManager.Listing l, boolean mine, long now) {
        ItemStack item = l.item.clone();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Text.lore("&8――――――――――"));
        lore.add(Text.lore("&7ราคา: &a$" + Text.money(l.price)));
        lore.add(Text.lore("&7ผู้ขาย: &f" + l.sellerName));
        if (mine && !l.expired(now)) {
            lore.add(Text.lore("&7คุณจะได้รับ: &a$" + Text.money(net(l.price))
                    + " &8(หักค่าธรรมเนียม " + Text.money(fee()) + "%)"));
        }
        if (l.expired(now)) {
            lore.add(Text.lore("&cหมดเวลาแล้ว"));
        } else {
            lore.add(Text.lore("&7เหลือเวลา: &f" + Text.duration(l.expiresAt - now)));
        }
        lore.add(Text.lore(" "));
        if (mine) {
            lore.add(Text.lore(l.expired(now) ? "&eคลิกซ้าย &7รับของคืน" : "&eคลิกซ้าย &7ยกเลิกและรับของคืน"));
        } else {
            lore.add(Text.lore("&eคลิกซ้าย &7ซื้อ"));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /** /ah sell <price> : lists the item in the main hand. */
    public void createListing(Player p, String priceArg) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(Text.c("&cถือของที่จะขายไว้ในมือก่อน"));
            return;
        }
        double price = Text.parseAmount(priceArg);
        double maxPrice = plugin.getConfig().getDouble("ah.max-price", 1_000_000_000d);
        if (price <= 0 || price > maxPrice) {
            p.sendMessage(Text.c("&cราคาไม่ถูกต้อง (ต้องมากกว่า 0 และไม่เกิน $" + Text.money(maxPrice) + ")"));
            return;
        }
        int maxListings = plugin.getConfig().getInt("ah.max-listings", 10);
        if (auctions.countByOwner(p.getUniqueId()) >= maxListings) {
            p.sendMessage(Text.c("&cลงขายได้สูงสุด " + maxListings + " รายการ (ดูที่ /ah mine)"));
            return;
        }
        long hours = plugin.getConfig().getLong("ah.duration-hours", 48L);
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        auctions.create(p.getUniqueId(), p.getName(), price,
                System.currentTimeMillis() + hours * 3_600_000L, item);
        p.sendMessage(Text.c("&aลงขาย &f" + Gui.describe(item) + " &aราคา &e$" + Text.money(price)
                + " &7(อยู่ได้ " + hours + " ชม.)"));
        if (fee() > 0) {
            p.sendMessage(Text.c("&7เมื่อขายได้จะหักค่าธรรมเนียม " + Text.money(fee())
                    + "% คุณจะได้รับ &a$" + Text.money(net(price))));
        }
    }

    private void buy(Player p, int id) {
        long now = System.currentTimeMillis();
        AuctionManager.Listing l = auctions.find(id);
        if (l == null || l.expired(now)) {
            p.sendMessage(Text.c("&cรายการนี้ถูกซื้อหรือหมดเวลาไปแล้ว"));
            return;
        }
        if (l.seller.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cซื้อของที่ตัวเองลงขายไม่ได้ (ยกเลิกได้ที่ รายการของฉัน)"));
            return;
        }
        if (!Gui.canFit(p.getInventory(), l.item)) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        if (!economy.take(p.getUniqueId(), l.price)) {
            p.sendMessage(Text.c("&cเงินไม่พอ ต้องใช้ &a$" + Text.money(l.price)));
            return;
        }
        auctions.remove(l);
        p.getInventory().addItem(l.item.clone());
        double net = net(l.price);
        economy.add(l.seller, net);
        economy.save();

        p.sendMessage(Text.c("&aซื้อ &f" + Gui.describe(l.item) + " &aราคา &e$" + Text.money(l.price)));
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            seller.sendMessage(Text.c("&a" + p.getName() + " ซื้อ &f" + Gui.describe(l.item)
                    + " &aของคุณ ได้เงิน &e$" + Text.money(net)
                    + (fee() > 0 ? " &7(หักค่าธรรมเนียม " + Text.money(fee()) + "%)" : "")));
        }
    }

    private void reclaim(Player p, int id) {
        AuctionManager.Listing l = auctions.find(id);
        if (l == null || !l.seller.equals(p.getUniqueId())) return;
        if (!Gui.canFit(p.getInventory(), l.item)) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        boolean expired = l.expired(System.currentTimeMillis());
        auctions.remove(l);
        p.getInventory().addItem(l.item.clone());
        p.sendMessage(Text.c(expired ? "&aรับของคืนแล้ว" : "&aยกเลิกรายการและรับของคืนแล้ว"));
    }

    private void reopen(Player p, int page, boolean mine) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, page, mine));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof AhHolder holder)) return;
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
        if (raw >= PAGE_SIZE || !e.isLeftClick()) return;

        int id = holder.ids[raw];
        if (id == 0) return;
        if (holder.mine) reclaim(p, id);
        else buy(p, id);
        reopen(p, holder.page, holder.mine);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof AhHolder) e.setCancelled(true);
    }

    private static final class AhHolder implements InventoryHolder {
        final boolean mine;
        final int page;
        final int[] ids = new int[PAGE_SIZE];
        Inventory inventory;

        AhHolder(boolean mine, int page) {
            this.mine = mine;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
