package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Special shop (Nether Star button in /shop): Netherite Pickaxe 3x3 and spawner cages. */
public final class SpecialShopGui implements Listener {

    private static final double MIN_SPAWNER_PRICE = 300_000d;
    private static final double DEFAULT_HAMMER_PRICE = 30_000_000d;
    private static final Map<EntityType, Double> DEFAULT_PRICES = new LinkedHashMap<>();

    static {
        DEFAULT_PRICES.put(EntityType.PIG, 300_000d);
        DEFAULT_PRICES.put(EntityType.COW, 300_000d);
        DEFAULT_PRICES.put(EntityType.CHICKEN, 300_000d);
        DEFAULT_PRICES.put(EntityType.SHEEP, 320_000d);
        DEFAULT_PRICES.put(EntityType.ZOMBIE, 350_000d);
        DEFAULT_PRICES.put(EntityType.SPIDER, 380_000d);
        DEFAULT_PRICES.put(EntityType.CAVE_SPIDER, 380_000d);
        DEFAULT_PRICES.put(EntityType.SKELETON, 400_000d);
        DEFAULT_PRICES.put(EntityType.CREEPER, 600_000d);
        DEFAULT_PRICES.put(EntityType.SLIME, 650_000d);
        DEFAULT_PRICES.put(EntityType.MAGMA_CUBE, 650_000d);
        DEFAULT_PRICES.put(EntityType.ZOMBIFIED_PIGLIN, 700_000d);
        DEFAULT_PRICES.put(EntityType.WITCH, 900_000d);
        DEFAULT_PRICES.put(EntityType.BLAZE, 1_200_000d);
        DEFAULT_PRICES.put(EntityType.ENDERMAN, 1_500_000d);
        DEFAULT_PRICES.put(EntityType.WITHER_SKELETON, 2_000_000d);
        DEFAULT_PRICES.put(EntityType.IRON_GOLEM, 3_000_000d);
    }

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final HammerManager hammer;
    private final SpawnerManager spawners;
    private final ShopGui shop;

    private double hammerPrice = DEFAULT_HAMMER_PRICE;
    private final Map<EntityType, Double> prices = new LinkedHashMap<>();

    public SpecialShopGui(JavaPlugin plugin, EconomyManager economy, HammerManager hammer,
                          SpawnerManager spawners, ShopGui shop) {
        this.plugin = plugin;
        this.economy = economy;
        this.hammer = hammer;
        this.spawners = spawners;
        this.shop = shop;
    }

    public void reload() {
        plugin.getConfig().addDefault("special-shop.hammer-price", (long) DEFAULT_HAMMER_PRICE);
        for (Map.Entry<EntityType, Double> e : DEFAULT_PRICES.entrySet()) {
            plugin.getConfig().addDefault("special-shop.spawner-prices." + e.getKey().name(), e.getValue().longValue());
        }
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        hammerPrice = Math.max(1, plugin.getConfig().getDouble("special-shop.hammer-price", DEFAULT_HAMMER_PRICE));
        prices.clear();
        for (Map.Entry<EntityType, Double> e : DEFAULT_PRICES.entrySet()) {
            if (!SpawnerManager.supports(e.getKey())) continue;
            double v = plugin.getConfig().getDouble("special-shop.spawner-prices." + e.getKey().name(), e.getValue());
            prices.put(e.getKey(), Math.max(MIN_SPAWNER_PRICE, v)); // spawners never cost less than 300k
        }
    }

    public void open(Player p) {
        SpecialHolder holder = new SpecialHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, Text.c("&8ร้านค้าพิเศษ"));
        holder.inventory = inv;

        ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 18; i++) inv.setItem(i, pane);

        inv.setItem(4, hammerIcon());
        inv.setItem(13, Gui.named(Material.SPAWNER, "&#A855F7&lกรงสปาวเนอร์",
                "&7ผลิตของและ XP ให้เองโดยไม่ต้องมีม็อบ", "&7วางแล้วคลิกขวาที่กรงเพื่อเปิดเมนู"));

        int slot = 18;
        for (Map.Entry<EntityType, Double> e : prices.entrySet()) {
            if (slot >= 45) break;
            holder.types[slot] = e.getKey();
            inv.setItem(slot, spawnerIcon(e.getKey(), e.getValue()));
            slot++;
        }

        Gui.bottomRow(inv);
        inv.setItem(45, Gui.named(Material.ARROW, "&eกลับไปร้านค้า"));
        inv.setItem(49, balanceItem(p));
        p.openInventory(inv);
    }

    private ItemStack hammerIcon() {
        ItemStack item = hammer.createHammer();
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.add(Text.lore(" "));
        lore.add(Text.lore("&7ราคา: &a$" + Text.money(hammerPrice)));
        lore.add(Text.lore("&eคลิกเพื่อซื้อ"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack spawnerIcon(EntityType type, double price) {
        Material egg = Material.matchMaterial(type.name() + "_SPAWN_EGG");
        return Gui.named(egg != null ? egg : Material.SPAWNER,
                "&#C77DFF&l" + SpawnerManager.typeName(type) + " Spawner",
                "&7ราคา: &a$" + Text.money(price),
                "&7ผลิต: &f" + spawners.describeDrops(type),
                "&7ได้ XP และของโดยไม่ต้องมีม็อบ",
                " ",
                "&eคลิกเพื่อซื้อ");
    }

    private ItemStack balanceItem(Player p) {
        return Gui.named(Material.GOLD_INGOT, "&eเงินของคุณ: &a$" + Text.money(economy.get(p.getUniqueId())));
    }

    private void buyHammer(Player p) {
        buy(p, hammer.createHammer(), hammerPrice, "Netherite Pickaxe 3x3");
    }

    private void buySpawner(Player p, EntityType type) {
        Double price = prices.get(type);
        if (price == null) return;
        buy(p, spawners.createItem(type), price, SpawnerManager.typeName(type) + " Spawner");
    }

    private void buy(Player p, ItemStack item, double price, String name) {
        if (!Gui.canFit(p.getInventory(), item)) {
            p.sendMessage(Text.c("&cกระเป๋าเต็ม"));
            return;
        }
        if (!economy.take(p.getUniqueId(), price)) {
            p.sendMessage(Text.c("&cเงินไม่พอ ต้องใช้ &a$" + Text.money(price)));
            return;
        }
        p.getInventory().addItem(item);
        economy.save();
        p.sendMessage(Text.c("&aซื้อ &f" + name + " &aราคา &e$" + Text.money(price)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SpecialHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= 54 || !e.isLeftClick()) return;

        if (raw == 45) {
            Bukkit.getScheduler().runTask(plugin, () -> shop.open(p, 0));
            return;
        }
        if (raw == 4) buyHammer(p);
        else if (holder.types[raw] != null) buySpawner(p, holder.types[raw]);
        else return;
        holder.inventory.setItem(49, balanceItem(p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SpecialHolder) e.setCancelled(true);
    }

    private static final class SpecialHolder implements InventoryHolder {
        final EntityType[] types = new EntityType[54];
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
