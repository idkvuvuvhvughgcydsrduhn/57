package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Spawner menus: main menu (XP / production / sell / throw away) and the storage view. */
public final class SpawnerGui implements Listener {

    private static final int PAGE_SIZE = 45;

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final SpawnerManager manager;

    public SpawnerGui(JavaPlugin plugin, EconomyManager economy, SpawnerManager manager) {
        this.plugin = plugin;
        this.economy = economy;
        this.manager = manager;
    }

    // ------------------------------------------------------------------ main menu

    public void open(Player p, SpawnerManager.SpawnerData data) {
        SpHolder holder = new SpHolder(data, false, 0);
        Inventory inv = Bukkit.createInventory(holder, 27,
                Text.c("&8" + SpawnerManager.typeName(data.type) + " Spawner"));
        holder.inventory = inv;
        ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        fillMain(holder);
        p.openInventory(inv);
    }

    private Material icon(EntityType type) {
        Material egg = Material.matchMaterial(type.name() + "_SPAWN_EGG");
        return egg != null ? egg : Material.SPAWNER;
    }

    private void fillMain(SpHolder h) {
        SpawnerManager.SpawnerData d = h.data;
        Inventory inv = h.inventory;
        long stored = d.stored();
        double value = manager.valueOf(d);
        int cycle = Math.max(1, plugin.getConfig().getInt("spawners.cycle-seconds", 10));

        int maxStack = Math.max(1, plugin.getConfig().getInt("spawners.max-stack", 10000));
        inv.setItem(4, Gui.named(icon(d.type), "&#C77DFF&l" + SpawnerManager.typeName(d.type) + " Spawner",
                "&7เจ้าของ: &f" + d.ownerName,
                "&7ซ้อน: &f" + Math.max(1, d.stack) + "&7/&f" + maxStack,
                "&7ม็อบที่ผลิตแล้ว: &f" + PointsManager.format(d.mobs),
                "&7ของที่ผลิตได้ทั้งหมด: &f" + PointsManager.format(d.produced) + " ชิ้น",
                "&7ผลิตทุก &f" + cycle + " &7วินาที &8(×" + Math.max(1, d.stack) + ")",
                " ",
                "&eShift+คลิกขวา ถือกรงประเภทเดียวกันเพื่อซ้อน"));
        inv.setItem(10, Gui.named(Material.EXPERIENCE_BOTTLE, "&aรับ XP",
                "&7XP สะสม: &f" + PointsManager.format(d.xp), " ", "&eคลิกเพื่อรับ XP"));
        inv.setItem(12, Gui.named(Material.CHEST, "&eดูของที่ผลิตได้",
                "&7ในสปาวเนอร์: &f" + PointsManager.format(stored) + " ชิ้น &7(" + d.storage.size() + " อย่าง)",
                "&7มูลค่าโดยประมาณ: &a$" + Text.money(value), " ", "&eคลิกเพื่อดู / เอาของออก"));
        inv.setItem(14, Gui.named(Material.GOLD_INGOT, "&6ขายของทั้งหมด",
                "&7ได้เงินประมาณ: &a$" + Text.money(value), "&8ของที่ขายไม่ได้จะยังอยู่", " ", "&eคลิกเพื่อขาย"));
        inv.setItem(16, Gui.named(Material.LAVA_BUCKET, "&cทิ้งของทั้งหมด",
                "&7ลบของในสปาวเนอร์ทิ้ง (ไม่ได้เงิน)", " ", "&cShift + คลิก &7เพื่อยืนยัน"));
    }

    // ------------------------------------------------------------------ storage view

    private void openStorage(Player p, SpawnerManager.SpawnerData d, int page) {
        List<Map.Entry<Material, Long>> list = new ArrayList<>(d.storage.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int pages = Gui.pages(list.size(), PAGE_SIZE);
        int pg = Math.max(0, Math.min(page, pages - 1));

        SpHolder holder = new SpHolder(d, true, pg);
        Inventory inv = Bukkit.createInventory(holder, 54,
                Text.c("&8ของที่ผลิตได้ &7(" + (pg + 1) + "/" + pages + ")"));
        holder.inventory = inv;

        double mult = manager.sellMultiplier();
        int start = pg * PAGE_SIZE;
        for (int i = 0; i < PAGE_SIZE && start + i < list.size(); i++) {
            Material m = list.get(start + i).getKey();
            long count = list.get(start + i).getValue();
            holder.items[i] = m;

            ItemStack item = new ItemStack(m);
            item.setAmount((int) Math.max(1, Math.min(count, m.getMaxStackSize())));
            ItemMeta meta = item.getItemMeta();
            double price = manager.priceOf(m) * mult;
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            lore.add(Text.lore("&7จำนวน: &f" + PointsManager.format(count)));
            lore.add(Text.lore(price > 0
                    ? "&7ราคา: &a$" + Text.money(price) + " &7/ ชิ้น (รวม &a$" + Text.money(price * count) + "&7)"
                    : "&8ขายไม่ได้"));
            lore.add(Text.lore(" "));
            lore.add(Text.lore("&eคลิกซ้าย &7เอา 1 สแตก"));
            lore.add(Text.lore("&eShift + คลิก &7เอาให้มากที่สุดเท่าที่ใส่ได้"));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }

        Gui.bottomRow(inv);
        if (pg > 0) inv.setItem(45, Gui.named(Material.ARROW, "&eหน้าก่อนหน้า"));
        if (pg < pages - 1) inv.setItem(53, Gui.named(Material.ARROW, "&eหน้าถัดไป"));
        inv.setItem(49, Gui.named(Material.BARRIER, "&eกลับไปเมนูสปาวเนอร์"));
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ actions

    private void collectXp(Player p, SpawnerManager.SpawnerData d) {
        if (d.xp <= 0) {
            p.sendMessage(Text.c("&7ยังไม่มี XP ให้รับ"));
            return;
        }
        long xp = d.xp;
        d.xp = 0;
        p.giveExp((int) Math.min(xp, 2_000_000_000L));
        manager.touch();
        manager.save();
        p.sendMessage(Text.c("&aได้รับ XP &f" + PointsManager.format(xp)));
    }

    private void sellAll(Player p, SpawnerManager.SpawnerData d) {
        double mult = manager.sellMultiplier();
        double total = 0;
        long count = 0;
        Iterator<Map.Entry<Material, Long>> it = d.storage.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Material, Long> e = it.next();
            double price = manager.priceOf(e.getKey());
            if (price <= 0) continue;
            total += price * e.getValue() * mult;
            count += e.getValue();
            it.remove();
        }
        if (count == 0) {
            p.sendMessage(Text.c("&cไม่มีของที่ขายได้ในสปาวเนอร์"));
            return;
        }
        economy.add(p.getUniqueId(), total);
        economy.save();
        manager.touch();
        manager.save();
        p.sendMessage(Text.c("&aขายของ &f" + PointsManager.format(count) + " &aชิ้น ได้เงิน &e$" + Text.money(total)));
    }

    private void discard(Player p, SpawnerManager.SpawnerData d) {
        long n = d.stored();
        d.storage.clear();
        manager.touch();
        manager.save();
        p.sendMessage(Text.c("&cทิ้งของทั้งหมด " + PointsManager.format(n) + " ชิ้นแล้ว"));
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SpHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= e.getInventory().getSize()) return;
        if (!manager.canManage(p, holder.data)) {
            p.closeInventory();
            return;
        }

        if (holder.storage) storageClick(p, holder, raw, e);
        else mainClick(p, holder, raw, e);
    }

    private void mainClick(Player p, SpHolder h, int raw, InventoryClickEvent e) {
        SpawnerManager.SpawnerData d = h.data;
        switch (raw) {
            case 10 -> collectXp(p, d);
            case 12 -> {
                Bukkit.getScheduler().runTask(plugin, () -> openStorage(p, d, 0));
                return;
            }
            case 14 -> sellAll(p, d);
            case 16 -> {
                if (!e.isShiftClick()) {
                    p.sendMessage(Text.c("&cกด Shift + คลิก ที่ปุ่มทิ้งเพื่อยืนยัน"));
                    return;
                }
                discard(p, d);
            }
            default -> {
                return;
            }
        }
        fillMain(h);
    }

    private void storageClick(Player p, SpHolder h, int raw, InventoryClickEvent e) {
        SpawnerManager.SpawnerData d = h.data;
        ItemStack clicked = e.getCurrentItem();

        if ((raw == 45 || raw == 53) && Gui.isArrow(clicked)) {
            int target = raw == 45 ? h.page - 1 : h.page + 1;
            Bukkit.getScheduler().runTask(plugin, () -> openStorage(p, d, target));
            return;
        }
        if (raw == 49) {
            Bukkit.getScheduler().runTask(plugin, () -> open(p, d));
            return;
        }
        if (raw >= PAGE_SIZE || h.items[raw] == null || !e.isLeftClick()) return;

        Material m = h.items[raw];
        long have = d.storage.getOrDefault(m, 0L);
        if (have <= 0) return;
        long want = e.isShiftClick() ? Math.min(have, 64L * 36) : Math.min(have, m.getMaxStackSize());
        int given = Gui.give(p.getInventory(), m, (int) want);
        if (given <= 0) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        if (have - given <= 0) d.storage.remove(m);
        else d.storage.put(m, have - given);
        manager.touch();
        manager.save();
        Bukkit.getScheduler().runTask(plugin, () -> openStorage(p, d, h.page));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SpHolder) e.setCancelled(true);
    }

    private static final class SpHolder implements InventoryHolder {
        final SpawnerManager.SpawnerData data;
        final boolean storage;
        final int page;
        final Material[] items = new Material[PAGE_SIZE];
        Inventory inventory;

        SpHolder(SpawnerManager.SpawnerData data, boolean storage, int page) {
            this.data = data;
            this.storage = storage;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
