package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.SpawnerSpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Click-to-open spawner cages. A placed spawner does not spawn mobs; it produces the mob's drops
 * and XP on its own, stored inside it. The owner opens a menu (right click) to collect XP, view
 * the production, sell everything, or throw it away. Data: plugins/StructureScoreboard/spawners.yml
 */
public final class SpawnerManager implements Listener {

    public static final class SpawnerData {
        public final String world;
        public final int x, y, z;
        public final EntityType type;
        public final UUID owner;
        public final String ownerName;
        public long xp;
        public long mobs;
        public long produced;
        /** จำนวนกรงที่ซ้อนกัน (อย่างน้อย 1, สูงสุดตาม config) */
        public int stack = 1;
        public final Map<Material, Long> storage = new LinkedHashMap<>();

        SpawnerData(String world, int x, int y, int z, EntityType type, UUID owner, String ownerName) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = type;
            this.owner = owner;
            this.ownerName = ownerName;
        }

        public String key() {
            return world + ";" + x + ";" + y + ";" + z;
        }

        public long stored() {
            long n = 0;
            for (long v : storage.values()) n += v;
            return n;
        }
    }

    private record Drop(Material item, int min, int max, double chance) {
    }

    /** rate = how many mobs per cycle compared with the base value (passive animals are slower). */
    private record Table(double rate, int xp, List<Drop> drops) {
    }

    private static final Map<EntityType, Table> TABLES = new LinkedHashMap<>();

    private static Drop d(Material m, int min, int max, double chance) {
        return new Drop(m, min, max, chance);
    }

    static {
        TABLES.put(EntityType.PIG, new Table(0.5, 2, List.of(d(Material.PORKCHOP, 1, 3, 1.0))));
        TABLES.put(EntityType.COW, new Table(0.5, 2, List.of(d(Material.LEATHER, 0, 2, 1.0), d(Material.BEEF, 1, 3, 1.0))));
        TABLES.put(EntityType.CHICKEN, new Table(0.5, 2, List.of(d(Material.FEATHER, 0, 2, 1.0),
                d(Material.CHICKEN, 1, 1, 1.0), d(Material.EGG, 1, 1, 0.3))));
        TABLES.put(EntityType.SHEEP, new Table(0.5, 2, List.of(d(Material.MUTTON, 1, 2, 1.0), d(Material.WHITE_WOOL, 1, 1, 1.0))));
        TABLES.put(EntityType.ZOMBIE, new Table(1.0, 5, List.of(d(Material.ROTTEN_FLESH, 0, 2, 1.0),
                d(Material.IRON_INGOT, 1, 1, 0.025), d(Material.CARROT, 1, 1, 0.025), d(Material.POTATO, 1, 1, 0.025))));
        TABLES.put(EntityType.SKELETON, new Table(1.0, 5, List.of(d(Material.BONE, 0, 2, 1.0), d(Material.ARROW, 0, 2, 1.0))));
        TABLES.put(EntityType.SPIDER, new Table(1.0, 5, List.of(d(Material.STRING, 0, 2, 1.0), d(Material.SPIDER_EYE, 1, 1, 0.33))));
        TABLES.put(EntityType.CAVE_SPIDER, new Table(1.0, 5, List.of(d(Material.STRING, 0, 2, 1.0), d(Material.SPIDER_EYE, 1, 1, 0.33))));
        TABLES.put(EntityType.CREEPER, new Table(1.0, 5, List.of(d(Material.GUNPOWDER, 0, 2, 1.0))));
        TABLES.put(EntityType.ZOMBIFIED_PIGLIN, new Table(1.0, 5, List.of(d(Material.ROTTEN_FLESH, 0, 1, 1.0),
                d(Material.GOLD_NUGGET, 0, 1, 0.5), d(Material.GOLD_INGOT, 1, 1, 0.025))));
        TABLES.put(EntityType.SLIME, new Table(1.0, 4, List.of(d(Material.SLIME_BALL, 0, 2, 1.0))));
        TABLES.put(EntityType.MAGMA_CUBE, new Table(1.0, 4, List.of(d(Material.MAGMA_CREAM, 0, 1, 0.25))));
        TABLES.put(EntityType.WITCH, new Table(1.0, 5, List.of(d(Material.GLASS_BOTTLE, 0, 2, 1.0),
                d(Material.GLOWSTONE_DUST, 0, 2, 1.0), d(Material.GUNPOWDER, 0, 2, 1.0), d(Material.REDSTONE, 0, 2, 1.0),
                d(Material.SPIDER_EYE, 0, 2, 1.0), d(Material.SUGAR, 0, 2, 1.0), d(Material.STICK, 0, 2, 1.0))));
        TABLES.put(EntityType.BLAZE, new Table(1.0, 10, List.of(d(Material.BLAZE_ROD, 0, 1, 1.0))));
        TABLES.put(EntityType.ENDERMAN, new Table(1.0, 5, List.of(d(Material.ENDER_PEARL, 0, 1, 1.0))));
        TABLES.put(EntityType.WITHER_SKELETON, new Table(1.0, 5, List.of(d(Material.BONE, 0, 2, 1.0), d(Material.COAL, 0, 1, 0.33))));
        TABLES.put(EntityType.IRON_GOLEM, new Table(1.0, 0, List.of(d(Material.IRON_INGOT, 3, 5, 1.0), d(Material.POPPY, 0, 2, 1.0))));
    }

    /** Sell prices for drops that are not in prices.yml. */
    private static final Map<Material, Double> FALLBACK = Map.ofEntries(
            Map.entry(Material.PORKCHOP, 2.0), Map.entry(Material.BEEF, 2.0), Map.entry(Material.CHICKEN, 1.5),
            Map.entry(Material.MUTTON, 2.0), Map.entry(Material.WHITE_WOOL, 3.0), Map.entry(Material.GOLD_NUGGET, 4.0),
            Map.entry(Material.MAGMA_CREAM, 15.0), Map.entry(Material.GLASS_BOTTLE, 2.0),
            Map.entry(Material.POPPY, 1.0), Map.entry(Material.STICK, 1.0));

    private static final String PURPLE = "&#A855F7";

    private final JavaPlugin plugin;
    private final PriceManager prices;
    private final NamespacedKey typeKey;
    private final File file;
    private final Map<String, SpawnerData> spawners = new HashMap<>();
    private SpawnerGui gui;
    private boolean dirty;
    private BukkitTask produceTask;
    private BukkitTask saveTask;

    public SpawnerManager(JavaPlugin plugin, PriceManager prices) {
        this.plugin = plugin;
        this.prices = prices;
        this.typeKey = new NamespacedKey(plugin, "spawner_type");
        this.file = new File(plugin.getDataFolder(), "spawners.yml");
        load();
    }

    public void setGui(SpawnerGui gui) {
        this.gui = gui;
    }

    public void reload() {
        plugin.getConfig().addDefault("spawners.cycle-seconds", 10);
        plugin.getConfig().addDefault("spawners.mobs-per-cycle", 3);
        plugin.getConfig().addDefault("spawners.max-storage", 20000);
        plugin.getConfig().addDefault("spawners.sell-multiplier", 1.0);
        plugin.getConfig().addDefault("spawners.max-stack", 10000);
        // ขุดกรงธรรมชาติ
        plugin.getConfig().addDefault("spawners.natural-break", true);
        plugin.getConfig().addDefault("spawners.require-silk-touch", true);
        plugin.getConfig().addDefault("spawners.require-pickaxe", true);
        plugin.getConfig().addDefault("spawners.drop-chance", 100.0);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        if (produceTask != null) produceTask.cancel();
        long ticks = Math.max(1, plugin.getConfig().getInt("spawners.cycle-seconds", 10)) * 20L;
        produceTask = Bukkit.getScheduler().runTaskTimer(plugin, this::produce, ticks, ticks);
        if (saveTask == null) saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    public void stop() {
        if (produceTask != null) produceTask.cancel();
        if (saveTask != null) saveTask.cancel();
        save();
    }

    // ------------------------------------------------------------------ helpers used by the menus

    public static boolean supports(EntityType type) {
        return TABLES.containsKey(type);
    }

    public static Set<EntityType> supportedTypes() {
        return TABLES.keySet();
    }

    public static String typeName(EntityType type) {
        StringBuilder sb = new StringBuilder();
        for (String w : type.name().toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    public String describeDrops(EntityType type) {
        Table t = TABLES.get(type);
        if (t == null) return "-";
        List<String> names = new ArrayList<>();
        for (Drop dr : t.drops()) {
            if (names.size() >= 4) break;
            names.add(Text.pretty(dr.item()));
        }
        return String.join(", ", names);
    }

    public double priceOf(Material m) {
        double v = prices.sellPrice(m);
        return v > 0 ? v : FALLBACK.getOrDefault(m, 0.0);
    }

    public double sellMultiplier() {
        return Math.max(0, plugin.getConfig().getDouble("spawners.sell-multiplier", 1.0));
    }

    public int maxStack() {
        return Math.max(1, plugin.getConfig().getInt("spawners.max-stack", 10000));
    }

    public double valueOf(SpawnerData data) {
        double total = 0;
        for (Map.Entry<Material, Long> e : data.storage.entrySet()) {
            total += priceOf(e.getKey()) * e.getValue();
        }
        return total * sellMultiplier();
    }

    public boolean canManage(Player p, SpawnerData data) {
        return p.getUniqueId().equals(data.owner) || p.hasPermission("structuresmp.admin");
    }

    /** Every placed cage (used by the anti-ESP to hide them from players who should not see them). */
    public java.util.Collection<SpawnerData> all() {
        return spawners.values();
    }

    public void touch() {
        dirty = true;
    }

    // ------------------------------------------------------------------ item

    public ItemStack createItem(EntityType type) {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore("&#C77DFF&l" + typeName(type) + " Spawner"));
        meta.lore(List.of(
                Text.lore("&7กรงสปาวเนอร์ ผลิตของให้เองโดยไม่ต้องมีม็อบ"),
                Text.lore("&7วางแล้วคลิกขวาที่กรงเพื่อเปิดเมนู"),
                Text.lore(" "),
                Text.lore(PURPLE + "Structure Smp")));
        meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.name());
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(type);
            bsm.setBlockState(cs);
        }
        item.setItemMeta(meta);
        return item;
    }

    private EntityType typeOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String name = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (name == null) return null;
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    // ------------------------------------------------------------------ production

    private void produce() {
        int base = Math.max(1, plugin.getConfig().getInt("spawners.mobs-per-cycle", 3));
        long cap = Math.max(1, plugin.getConfig().getLong("spawners.max-storage", 20000L));
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<String> invalid = new ArrayList<>();

        for (SpawnerData data : spawners.values()) {
            World w = Bukkit.getWorld(data.world);
            if (w == null || !w.isChunkLoaded(data.x >> 4, data.z >> 4)) continue;
            if (w.getBlockAt(data.x, data.y, data.z).getType() != Material.SPAWNER) {
                invalid.add(data.key());
                continue;
            }
            Table t = TABLES.get(data.type);
            if (t == null) continue;

            int stack = Math.max(1, data.stack);
            // จำนวนม็อบต่อรอบ × จำนวนกรงที่ซ้อน
            int mobs = Math.max(1, (int) Math.round(base * t.rate() * stack));
            long stored = data.stored();
            data.mobs += mobs;
            data.xp += (long) t.xp() * mobs;
            for (int i = 0; i < mobs; i++) {
                for (Drop dr : t.drops()) {
                    if (rnd.nextDouble() > dr.chance()) continue;
                    long n = dr.min() + rnd.nextInt(dr.max() - dr.min() + 1);
                    n = Math.min(n, cap - stored);
                    if (n <= 0) continue;
                    data.storage.merge(dr.item(), n, Long::sum);
                    stored += n;
                    data.produced += n;
                }
            }
        }
        for (String k : invalid) spawners.remove(k);
        dirty = true;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        EntityType type = typeOf(e.getItemInHand());
        Block b = e.getBlockPlaced();
        if (type == null || b.getType() != Material.SPAWNER) return;

        Player p = e.getPlayer();
        spawners.put(key(b), new SpawnerData(b.getWorld().getName(), b.getX(), b.getY(), b.getZ(),
                type, p.getUniqueId(), p.getName()));
        if (b.getState() instanceof CreatureSpawner cs) {
            cs.setSpawnedType(type);
            cs.update();
        }
        dirty = true;
        save();
        p.sendMessage(Text.c(PURPLE + "วาง " + typeName(type) + " Spawner แล้ว คลิกขวาที่กรงเพื่อเปิดเมนู"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.SPAWNER) return;

        Player p = e.getPlayer();
        SpawnerData data = spawners.get(key(b));

        // ----- กรงที่ระบบจัดการ (ของ player) -----
        if (data != null) {
            if (!canManage(p, data)) {
                e.setCancelled(true);
                p.sendMessage(Text.c("&cสปาวเนอร์นี้เป็นของ " + data.ownerName));
                return;
            }
            if (data.stored() > 0) {
                e.setCancelled(true);
                p.sendMessage(Text.c(PURPLE + "ยังมีของอยู่ในสปาวเนอร์ ขายหรือทิ้งก่อนแล้วค่อยทุบ"));
                return;
            }

            e.setDropItems(false);
            e.setExpToDrop(0);
            spawners.remove(key(b));
            dirty = true;
            if (data.xp > 0) p.giveExp((int) Math.min(data.xp, 2_000_000_000L));
            // ดรอปตามจำนวนที่ซ้อน
            int amount = Math.max(1, data.stack);
            ItemStack drop = createItem(data.type);
            // ดรอปเป็นกองๆ (สูงสุด 64 ต่อกอง)
            while (amount > 0) {
                int n = Math.min(64, amount);
                ItemStack stack = drop.clone();
                stack.setAmount(n);
                b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), stack);
                amount -= n;
            }
            save();
            return;
        }

        // ----- กรงธรรมชาติ (vanilla) → ขุดด้วย Silk Touch ได้ -----
        if (!plugin.getConfig().getBoolean("spawners.natural-break", true)) return;
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        ItemStack tool = p.getInventory().getItemInMainHand();
        boolean requirePickaxe = plugin.getConfig().getBoolean("spawners.require-pickaxe", true);
        boolean requireSilk = plugin.getConfig().getBoolean("spawners.require-silk-touch", true);
        double chance = Math.max(0, Math.min(100, plugin.getConfig().getDouble("spawners.drop-chance", 100.0)));

        if (requirePickaxe && (tool == null || !tool.getType().name().endsWith("_PICKAXE"))) {
            return; // ไม่ใช่พิคแอ็กซ์ → พังตามปกติ ไม่ดรอป
        }
        if (requireSilk && (tool == null || !tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH))) {
            return; // ไม่มี Silk Touch → พังตามปกติ ไม่ดรอป
        }
        if (chance < 100.0 && ThreadLocalRandom.current().nextDouble() * 100.0 >= chance) {
            e.setDropItems(false);
            e.setExpToDrop(0);
            p.sendMessage(Text.c("&cกรงสปาวเนอร์แตกแล้ว แต่ไม่ได้ดรอป (โชคไม่ดี)"));
            return;
        }

        // อ่านประเภทม็อบจากบล็อก
        EntityType type = EntityType.PIG;
        if (b.getState() instanceof CreatureSpawner cs) {
            EntityType spawned = cs.getSpawnedType();
            if (spawned != null) type = spawned;
        }

        // ถ้าประเภทนี้ระบบไม่รองรับ ก็ยังดรอปเป็นไอเท็มธรรมดา (แต่จะวางแล้วไม่กลายเป็นกรงผลิต)
        e.setDropItems(false);
        e.setExpToDrop(0);

        if (supports(type)) {
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), createItem(type));
            p.sendMessage(Text.c(PURPLE + "ขุดกรงสปาวเนอร์ &f" + typeName(type) + PURPLE + " สำเร็จ! วางแล้วจะกลายเป็นกรงผลิตของ"));
        } else {
            // ประเภทที่ไม่รองรับ → ดรอปกรงเปล่า (ยังใช้ typeKey ได้ แต่จะไม่ผลิต)
            ItemStack item = createItem(type);
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), item);
            p.sendMessage(Text.c(PURPLE + "ขุดกรงสปาวเนอร์ &f" + typeName(type) + PURPLE + " สำเร็จ! &7(ประเภทนี้ยังไม่รองรับการผลิต)"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.SPAWNER) return;
        SpawnerData data = spawners.get(key(b));
        if (data == null) return;

        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();

        // ----- ซ้อนกรง: ย่อ + ถือกรงประเภทเดียวกัน -----
        if (p.isSneaking() && hand.getType() == Material.SPAWNER) {
            EntityType heldType = typeOf(hand);
            if (heldType != null && heldType == data.type) {
                e.setCancelled(true);
                if (!canManage(p, data)) {
                    p.sendMessage(Text.c("&cสปาวเนอร์นี้เป็นของ " + data.ownerName));
                    return;
                }
                int max = maxStack();
                if (data.stack >= max) {
                    p.sendMessage(Text.c("&cกรงนี้ซ้อนครบ " + max + " แล้ว"));
                    return;
                }
                // ซ้อนทีละ 1 จากมือ
                data.stack++;
                hand.setAmount(hand.getAmount() - 1);
                dirty = true;
                save();
                p.sendMessage(Text.c(PURPLE + "ซ้อนกรงแล้ว &f" + data.stack + "/" + max + " &7(" + typeName(data.type) + ")"));
                return;
            }
            // กรงคนละประเภท หรือไม่มี typeKey → ปล่อยให้วางบล็อกตามปกติ
            return;
        }

        // ย่อ + ถือของอื่น → ปล่อยให้วางบล็อกทับข้างๆ
        boolean egg = hand.getType().name().endsWith("_SPAWN_EGG");
        if (p.isSneaking() && !hand.getType().isAir() && !egg) return;

        e.setCancelled(true);
        if (!canManage(p, data)) {
            p.sendMessage(Text.c("&cสปาวเนอร์นี้เป็นของ " + data.ownerName));
            return;
        }
        if (gui != null) gui.open(p, data);
    }

    /** Our cages never spawn real mobs. */
    @EventHandler
    public void onSpawn(SpawnerSpawnEvent e) {
        CreatureSpawner cs = e.getSpawner();
        if (cs != null && spawners.containsKey(key(cs.getBlock()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> spawners.containsKey(key(b)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> spawners.containsKey(key(b)));
    }

    // ------------------------------------------------------------------ saving

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("spawners");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            try {
                SpawnerData data = new SpawnerData(s.getString("world", ""), s.getInt("x"), s.getInt("y"), s.getInt("z"),
                        EntityType.valueOf(s.getString("type", "")),
                        UUID.fromString(s.getString("owner", "")), s.getString("owner-name", "???"));
                data.xp = s.getLong("xp");
                data.mobs = s.getLong("mobs");
                data.produced = s.getLong("produced");
                data.stack = Math.max(1, s.getInt("stack", 1));
                ConfigurationSection st = s.getConfigurationSection("storage");
                if (st != null) {
                    for (String name : st.getKeys(false)) {
                        Material m = Material.matchMaterial(name);
                        if (m != null && st.getLong(name) > 0) data.storage.put(m, st.getLong(name));
                    }
                }
                spawners.put(data.key(), data);
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    public void save() {
        if (!dirty) return;
        YamlConfiguration yml = new YamlConfiguration();
        int n = 0;
        for (SpawnerData data : spawners.values()) {
            String base = "spawners." + (n++);
            yml.set(base + ".world", data.world);
            yml.set(base + ".x", data.x);
            yml.set(base + ".y", data.y);
            yml.set(base + ".z", data.z);
            yml.set(base + ".type", data.type.name());
            yml.set(base + ".owner", data.owner.toString());
            yml.set(base + ".owner-name", data.ownerName);
            yml.set(base + ".xp", data.xp);
            yml.set(base + ".mobs", data.mobs);
            yml.set(base + ".produced", data.produced);
            yml.set(base + ".stack", Math.max(1, data.stack));
            for (Map.Entry<Material, Long> e : data.storage.entrySet()) {
                yml.set(base + ".storage." + e.getKey().name(), e.getValue());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save spawners.yml: " + ex.getMessage());
        }
    }
}
