package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /repair — ถือไอเท็มที่เสียหาย แล้วเปิด GUI ยืนยันซ่อม
 * ราคา = ราคาซื้อในร้าน × ค่า multiplier (config 1.5–2.5) × สัดส่วนความเสียหาย
 */
public final class RepairGui implements Listener, CommandExecutor {

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PriceManager prices;

    public RepairGui(JavaPlugin plugin, EconomyManager economy, PriceManager prices) {
        this.plugin = plugin;
        this.economy = economy;
        this.prices = prices;
    }

    public void reload() {
        plugin.getConfig().addDefault("repair.enabled", true);
        plugin.getConfig().addDefault("repair.cost-multiplier", 2.0); // 1.5–2.5 แนะนำ
        plugin.getConfig().addDefault("repair.min-multiplier", 1.5);
        plugin.getConfig().addDefault("repair.max-multiplier", 2.5);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (!plugin.getConfig().getBoolean("repair.enabled", true)) {
            p.sendMessage(Text.c("&cระบบซ่อมถูกปิดอยู่"));
            return true;
        }

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            p.sendMessage(Text.c("&cถือไอเท็มที่ต้องการซ่อมไว้ในมือก่อน"));
            return true;
        }
        if (!isRepairable(hand)) {
            p.sendMessage(Text.c("&cไอเท็มนี้ซ่อมไม่ได้ หรือไม่เสียหาย"));
            return true;
        }

        open(p, hand);
        return true;
    }

    private boolean isRepairable(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return false;
        if (!meta.isUnbreakable() && item.getType().getMaxDurability() > 0) {
            return dmg.getDamage() > 0;
        }
        return false;
    }

    /** คำนวณราคาซ่อม */
    public double repairCost(ItemStack item) {
        Material m = item.getType();
        short max = m.getMaxDurability();
        if (max <= 0) return 0;

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return 0;
        int damage = dmg.getDamage();
        if (damage <= 0) return 0;

        double buy = prices.buyPrice(m);
        // ถ้าไม่มีราคาซื้อ ใช้ราคาขาย × 3 เป็นฐาน หรือค่าขั้นต่ำ
        if (buy <= 0) {
            double sell = prices.sellPrice(m);
            buy = sell > 0 ? sell * 3.0 : 100.0;
        }

        double mult = plugin.getConfig().getDouble("repair.cost-multiplier", 2.0);
        double min = plugin.getConfig().getDouble("repair.min-multiplier", 1.5);
        double maxM = plugin.getConfig().getDouble("repair.max-multiplier", 2.5);
        mult = Math.max(min, Math.min(maxM, mult));

        // สัดส่วนความเสียหาย (เสียหายเต็ม = จ่ายเต็ม)
        double ratio = (double) damage / (double) max;
        ratio = Math.max(0.05, Math.min(1.0, ratio)); // อย่างน้อย 5%

        return Math.ceil(buy * mult * ratio);
    }

    // ------------------------------------------------------------------ GUI

    public void open(Player p, ItemStack hand) {
        double cost = repairCost(hand);
        if (cost <= 0) {
            p.sendMessage(Text.c("&cคำนวณราคาซ่อมไม่ได้"));
            return;
        }

        RepairHolder holder = new RepairHolder(cost);
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&8ซ่อมแซมไอเท็ม"));
        holder.inventory = inv;

        // กระจกม่วง
        ItemStack pane = named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);

        // แสดงไอเท็มตรงกลาง
        ItemStack preview = hand.clone();
        ItemMeta meta = preview.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta != null && meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Text.lore(" "));
        lore.add(Text.lore("&7ความเสียหาย: &c" + damagePercent(hand) + "%"));
        lore.add(Text.lore("&7ราคาซ่อม: &a$" + Text.money(cost)));
        if (meta != null) {
            meta.lore(lore);
            preview.setItemMeta(meta);
        }
        inv.setItem(13, preview);

        // ปุ่มยืนยัน
        inv.setItem(11, named(Material.LIME_CONCRETE, "&a&lยืนยันซ่อม",
                "&7ราคา: &a$" + Text.money(cost),
                "&7เงินของคุณ: &f$" + Text.money(economy.get(p.getUniqueId())),
                " ",
                "&eคลิกเพื่อซ่อม"));

        // ปุ่มยกเลิก
        inv.setItem(15, named(Material.RED_CONCRETE, "&c&lยกเลิก",
                " ", "&eคลิกเพื่อปิด"));

        p.openInventory(inv);
    }

    private int damagePercent(ItemStack item) {
        short max = item.getType().getMaxDurability();
        if (max <= 0) return 0;
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable dmg)) return 0;
        return (int) Math.round(100.0 * dmg.getDamage() / max);
    }

    private static ItemStack named(Material m, String name, String... loreLines) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore(name));
        if (loreLines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(Text.lore(line));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof RepairHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw == 15) {
            p.closeInventory();
            return;
        }
        if (raw != 11) return;

        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isRepairable(hand)) {
            p.sendMessage(Text.c("&cไอเท็มในมือเปลี่ยนไปแล้ว หรือซ่อมไม่ได้"));
            p.closeInventory();
            return;
        }

        // คำนวณใหม่จากของในมือจริง (กันสลับไอเท็ม)
        double cost = repairCost(hand);
        UUID id = p.getUniqueId();
        if (!economy.take(id, cost)) {
            p.sendMessage(Text.c("&cเงินไม่พอ ต้องใช้ &a$" + Text.money(cost)
                    + " &c(คุณมี &f$" + Text.money(economy.get(id)) + "&c)"));
            return;
        }

        ItemMeta meta = hand.getItemMeta();
        if (meta instanceof Damageable dmg) {
            dmg.setDamage(0);
            hand.setItemMeta(meta);
        }
        economy.save();
        p.sendMessage(Text.c("&aซ่อมสำเร็จ! จ่าย &e$" + Text.money(cost)));
        p.closeInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof RepairHolder) e.setCancelled(true);
    }

    private static final class RepairHolder implements InventoryHolder {
        final double cost;
        Inventory inventory;

        RepairHolder(double cost) {
            this.cost = cost;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
