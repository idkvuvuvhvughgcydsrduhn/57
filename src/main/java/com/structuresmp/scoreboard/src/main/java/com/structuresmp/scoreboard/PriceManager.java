package com.structuresmp.scoreboard;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shop (buy) and worth (sell) prices, stored in plugins/StructureScoreboard/prices.yml */
public final class PriceManager {

    /** Bump this when the default price list changes; the old prices.yml is then saved as prices-old.yml. */
    private static final int CATALOG_VERSION = 2;

    // ITEM:buy:sell   (buy 0 = not sold in /shop, sell 0 = cannot be sold)
    // Shop order = order of this list. Ores and starter items cost at most 250.
    private static final String[] DEFAULTS = {
            "OAK_LOG:20:6", "SPRUCE_LOG:20:6", "BIRCH_LOG:20:6", "JUNGLE_LOG:20:6",
            "ACACIA_LOG:20:6", "DARK_OAK_LOG:20:6", "MANGROVE_LOG:20:6", "CHERRY_LOG:20:6",
            "PALE_OAK_LOG:20:6", "OAK_PLANKS:6:2", "SPRUCE_PLANKS:6:2", "BIRCH_PLANKS:6:2",
            "JUNGLE_PLANKS:6:2", "ACACIA_PLANKS:6:2", "DARK_OAK_PLANKS:6:2", "MANGROVE_PLANKS:6:2",
            "CHERRY_PLANKS:6:2", "PALE_OAK_PLANKS:6:2", "BAMBOO_PLANKS:6:2", "STICK:2:0",
            "COBBLESTONE:8:2", "STONE:10:3", "COBBLED_DEEPSLATE:10:3", "ANDESITE:8:2",
            "DIORITE:8:2", "GRANITE:8:2", "TUFF:8:2", "DIRT:5:1",
            "GRASS_BLOCK:10:3", "SAND:8:2", "RED_SAND:8:2", "GRAVEL:8:2",
            "CLAY_BALL:10:3", "SANDSTONE:12:4", "GLASS:12:4", "BRICK:15:4",
            "NETHERRACK:3:1", "SOUL_SAND:10:3", "BASALT:6:2", "BLACKSTONE:8:2",
            "OBSIDIAN:120:40", "END_STONE:20:6", "ICE:15:4", "MOSS_BLOCK:20:6",
            "CACTUS:12:4", "BAMBOO:6:2", "KELP:5:1.5", "LILY_PAD:12:3",
            "SUGAR_CANE:12:4", "PUMPKIN:25:8", "WHEAT_SEEDS:4:1", "BEETROOT_SEEDS:4:1",
            "WHEAT:15:5", "CARROT:15:5", "POTATO:15:5", "BEETROOT:15:5",
            "APPLE:20:6", "SWEET_BERRIES:10:3", "GLOW_BERRIES:15:4", "COCOA_BEANS:15:4",
            "EGG:10:3", "SUGAR:10:3", "PAPER:8:2", "BOOK:40:12",
            "TORCH:5:1", "CRAFTING_TABLE:30:8", "FURNACE:40:12", "CHEST:50:15",
            "OAK_BOAT:40:10", "FLINT:6:2", "LEATHER:25:8", "FEATHER:8:2",
            "STRING:12:4", "BONE:15:5", "ROTTEN_FLESH:0:1", "SPIDER_EYE:15:5",
            "GUNPOWDER:30:10", "SLIME_BALL:40:12", "INK_SAC:15:5", "GLOWSTONE_DUST:20:6",
            "ARROW:6:2", "BUCKET:180:60", "BREAD:30:10", "COOKED_BEEF:40:14",
            "COOKED_PORKCHOP:40:14", "COOKED_CHICKEN:30:10", "COOKED_MUTTON:35:12", "COOKED_RABBIT:30:10",
            "COOKED_SALMON:30:10", "COOKED_COD:25:8", "BAKED_POTATO:25:8", "PUMPKIN_PIE:40:12",
            "COOKIE:8:2", "GOLDEN_CARROT:120:35", "MUSHROOM_STEW:25:8", "HONEY_BOTTLE:40:12",
            "DRIED_KELP:5:1.5", "COAL:15:4", "CHARCOAL:12:3", "RAW_COPPER:15:5",
            "COPPER_INGOT:25:8", "RAW_IRON:40:14", "IRON_INGOT:60:20", "RAW_GOLD:70:24",
            "GOLD_INGOT:100:35", "REDSTONE:15:5", "LAPIS_LAZULI:15:5", "QUARTZ:25:8",
            "EMERALD:200:70", "DIAMOND:250:90", "NETHERITE_SCRAP:250:90", "WOODEN_SWORD:40:11",
            "WOODEN_AXE:60:17", "WOODEN_SPEAR:50:10", "WOODEN_PICKAXE:60:17", "WOODEN_SHOVEL:30:8",
            "WOODEN_HOE:40:12", "STONE_SWORD:60:14", "STONE_AXE:90:22", "STONE_SPEAR:70:14",
            "STONE_PICKAXE:90:22", "STONE_SHOVEL:45:9.5", "STONE_HOE:60:16", "COPPER_SWORD:200:41",
            "COPPER_AXE:300:63", "COPPER_SPEAR:220:44", "COPPER_PICKAXE:300:63", "COPPER_SHOVEL:100:23",
            "COPPER_HOE:200:43", "IRON_SWORD:600:97", "IRON_AXE:900:145", "IRON_SPEAR:650:130",
            "IRON_PICKAXE:900:145", "IRON_SHOVEL:300:51", "IRON_HOE:600:99", "GOLDEN_SWORD:800:160",
            "GOLDEN_AXE:1200:240", "GOLDEN_SPEAR:850:170", "GOLDEN_PICKAXE:1200:240", "GOLDEN_SHOVEL:400:83",
            "GOLDEN_HOE:800:160", "DIAMOND_SWORD:2600:400", "DIAMOND_AXE:3200:600", "DIAMOND_SPEAR:2800:560",
            "DIAMOND_PICKAXE:3000:600", "DIAMOND_SHOVEL:2500:200", "DIAMOND_HOE:2500:400", "NETHERITE_SWORD:18000:3600",
            "NETHERITE_AXE:22000:4400", "NETHERITE_SPEAR:19000:3800", "NETHERITE_PICKAXE:22000:4400", "NETHERITE_SHOVEL:16000:3200",
            "NETHERITE_HOE:16000:3200", "BOW:300:33", "CROSSBOW:450:125", "SHIELD:350:76",
            "TRIDENT:12000:3500", "MACE:60000:15000", "LEATHER_HELMET:90:27", "LEATHER_CHESTPLATE:140:42",
            "LEATHER_LEGGINGS:120:36", "LEATHER_BOOTS:70:21", "COPPER_HELMET:300:90", "COPPER_CHESTPLATE:480:140",
            "COPPER_LEGGINGS:420:125", "COPPER_BOOTS:240:72", "CHAINMAIL_HELMET:600:180", "CHAINMAIL_CHESTPLATE:960:285",
            "CHAINMAIL_LEGGINGS:840:250", "CHAINMAIL_BOOTS:480:140", "IRON_HELMET:700:210", "IRON_CHESTPLATE:1100:330",
            "IRON_LEGGINGS:950:285", "IRON_BOOTS:600:180", "GOLDEN_HELMET:900:270", "GOLDEN_CHESTPLATE:1400:420",
            "GOLDEN_LEGGINGS:1250:375", "GOLDEN_BOOTS:800:240", "DIAMOND_HELMET:3000:900", "DIAMOND_CHESTPLATE:4800:1400",
            "DIAMOND_LEGGINGS:4200:1250", "DIAMOND_BOOTS:2500:750", "NETHERITE_HELMET:20000:4000", "NETHERITE_CHESTPLATE:32000:6350",
            "NETHERITE_LEGGINGS:28000:5600", "NETHERITE_BOOTS:16000:3200", "TURTLE_HELMET:1500:450", "EXPERIENCE_BOTTLE:100:30",
            "ENDER_PEARL:200:70", "BLAZE_ROD:100:35", "FISHING_ROD:80:20", "SHEARS:120:36",
            "FLINT_AND_STEEL:90:27", "COMPASS:350:90", "CLOCK:600:150", "BREWING_STAND:250:60",
            "ENCHANTING_TABLE:1200:300", "GOLDEN_APPLE:1000:250", "COAL_BLOCK:0:36", "COPPER_BLOCK:0:72",
            "RAW_COPPER_BLOCK:0:45", "IRON_BLOCK:0:180", "RAW_IRON_BLOCK:0:126", "GOLD_BLOCK:0:315",
            "RAW_GOLD_BLOCK:0:216", "REDSTONE_BLOCK:0:45", "LAPIS_BLOCK:0:45", "EMERALD_BLOCK:0:630",
            "DIAMOND_BLOCK:0:810", "NETHERITE_BLOCK:0:8100", "NETHERITE_INGOT:0:900", "ANCIENT_DEBRIS:0:80",
            "AMETHYST_SHARD:0:40", "ECHO_SHARD:0:3000", "PRISMARINE_SHARD:0:60", "PRISMARINE_CRYSTALS:0:80",
            "PHANTOM_MEMBRANE:0:150", "GHAST_TEAR:0:400", "DRAGON_BREATH:0:1000", "NAME_TAG:0:1500",
            "SADDLE:0:2000", "DISC_FRAGMENT_5:0:2500", "SPONGE:0:3000", "NAUTILUS_SHELL:0:5000",
            "WITHER_ROSE:0:5000", "DRAGON_EGG:0:5000000", "ELYTRA:0:1800000", "BEACON:0:1500000",
            "NETHER_STAR:0:1200000", "ENCHANTED_GOLDEN_APPLE:0:900000", "TOTEM_OF_UNDYING:0:700000", "DRAGON_HEAD:0:500000",
            "HEAVY_CORE:0:450000", "CONDUIT:0:400000", "HEART_OF_THE_SEA:0:300000", "WITHER_SKELETON_SKULL:0:250000",
            "MUSIC_DISC_PIGSTEP:0:180000", "MUSIC_DISC_OTHERSIDE:0:160000", "MUSIC_DISC_5:0:150000", "MUSIC_DISC_RELIC:0:140000",
            "MUSIC_DISC_CREATOR:0:130000", "MUSIC_DISC_PRECIPICE:0:125000", "MUSIC_DISC_CREATOR_MUSIC_BOX:0:120000", "SHULKER_BOX:0:120000",
            "CREEPER_HEAD:0:80000", "PIGLIN_HEAD:0:80000", "MUSIC_DISC_13:0:70000", "MUSIC_DISC_CAT:0:70000",
            "MUSIC_DISC_BLOCKS:0:70000", "MUSIC_DISC_CHIRP:0:70000", "MUSIC_DISC_FAR:0:70000", "MUSIC_DISC_MALL:0:70000",
            "MUSIC_DISC_MELLOHI:0:70000", "MUSIC_DISC_STAL:0:70000", "MUSIC_DISC_STRAD:0:70000", "MUSIC_DISC_WARD:0:70000",
            "MUSIC_DISC_11:0:70000", "MUSIC_DISC_WAIT:0:70000", "SHULKER_SHELL:0:55000", "ZOMBIE_HEAD:0:45000",
            "SKELETON_SKULL:0:45000", "SNIFFER_EGG:0:30000"
    };

    private final JavaPlugin plugin;
    private final WorthGui worthGui;
    private final Map<Material, Double> buy = new LinkedHashMap<>();
    private final Map<Material, Double> sell = new HashMap<>();
    private List<Material> buyList = new ArrayList<>();
    private List<Material> sellList = new ArrayList<>();

    public PriceManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.worthGui = new WorthGui(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(worthGui, plugin);
    }

    public WorthGui worthGui() {
        return worthGui;
    }

    public void load() {
        buy.clear();
        sell.clear();
        File file = new File(plugin.getDataFolder(), "prices.yml");

        boolean write = !file.exists();
        if (!write) {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
            if (old.getInt("catalog-version", 1) < CATALOG_VERSION) {
                File backup = new File(plugin.getDataFolder(), "prices-old.yml");
                if (backup.exists()) backup.delete();
                file.renameTo(backup);
                write = true;
            }
        }
        if (write) writeDefaults(file);

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null || !m.isItem()) {
                    plugin.getLogger().warning("Unknown item in prices.yml (skipped): " + key);
                    continue;
                }
                double b = section.getDouble(key + ".buy", 0);
                double s = section.getDouble(key + ".sell", 0);
                if (b > 0) buy.put(m, b);
                if (s > 0) sell.put(m, s);
            }
        }
        buyList = new ArrayList<>(buy.keySet());

        // worth list: most expensive first
        sellList = new ArrayList<>(sell.keySet());
        sellList.sort((a, b) -> {
            int c = Double.compare(sell.get(b), sell.get(a));
            return c != 0 ? c : a.name().compareTo(b.name());
        });
    }

    private void writeDefaults(File file) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("catalog-version", CATALOG_VERSION);
        for (String line : DEFAULTS) {
            String[] p = line.split(":");
            yml.set("items." + p[0] + ".buy", Double.parseDouble(p[1]));
            yml.set("items." + p[0] + ".sell", Double.parseDouble(p[2]));
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write prices.yml: " + e.getMessage());
        }
    }

    public double buyPrice(Material m) {
        return buy.getOrDefault(m, 0.0);
    }

    public double sellPrice(Material m) {
        return sell.getOrDefault(m, 0.0);
    }

    /** Items sold in /shop, in shop order. */
    public List<Material> buyItems() {
        return buyList;
    }

    /** Items that can be sold, most expensive first. */
    public List<Material> sellItems() {
        return sellList;
    }
}
