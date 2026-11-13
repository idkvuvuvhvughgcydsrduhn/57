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
    private static final int CATALOG_VERSION = 3;

    // ITEM:buy:sell   (buy 0 = not sold in /shop, sell 0 = cannot be sold)
    // Shop order = order of this list. Ores and starter items cost at most 250.
    private static final String[] DEFAULTS = {
            "OAK_LOG:45:6", "SPRUCE_LOG:45:6", "BIRCH_LOG:45:6", "JUNGLE_LOG:45:6",
            "ACACIA_LOG:45:6", "DARK_OAK_LOG:45:6", "MANGROVE_LOG:45:6", "CHERRY_LOG:45:6",
            "PALE_OAK_LOG:45:6", "OAK_PLANKS:13:2", "SPRUCE_PLANKS:13:2", "BIRCH_PLANKS:13:2",
            "JUNGLE_PLANKS:13:2", "ACACIA_PLANKS:13:2", "DARK_OAK_PLANKS:13:2", "MANGROVE_PLANKS:13:2",
            "CHERRY_PLANKS:13:2", "PALE_OAK_PLANKS:13:2", "BAMBOO_PLANKS:13:2", "STICK:4.5:0",
            "COBBLESTONE:18:2", "STONE:22:3", "COBBLED_DEEPSLATE:22:3", "ANDESITE:18:2",
            "DIORITE:18:2", "GRANITE:18:2", "TUFF:18:2", "DIRT:11:1",
            "GRASS_BLOCK:22:3", "SAND:18:2", "RED_SAND:18:2", "GRAVEL:18:2",
            "CLAY_BALL:22:3", "SANDSTONE:27:4", "GLASS:27:4", "BRICK:33:4",
            "NETHERRACK:6.5:1", "SOUL_SAND:22:3", "BASALT:13:2", "BLACKSTONE:18:2",
            "OBSIDIAN:250:40", "END_STONE:45:6", "ICE:33:4", "MOSS_BLOCK:45:6",
            "CACTUS:27:4", "BAMBOO:13:2", "KELP:11:1.5", "LILY_PAD:27:3",
            "SUGAR_CANE:27:4", "PUMPKIN:56:8", "WHEAT_SEEDS:9:1", "BEETROOT_SEEDS:9:1",
            "WHEAT:33:5", "CARROT:33:5", "POTATO:33:5", "BEETROOT:33:5",
            "APPLE:45:6", "SWEET_BERRIES:22:3", "GLOW_BERRIES:33:4", "COCOA_BEANS:33:4",
            "EGG:22:3", "SUGAR:22:3", "PAPER:18:2", "BOOK:90:12",
            "TORCH:11:1", "CRAFTING_TABLE:67:8", "FURNACE:90:12", "CHEST:110:15",
            "OAK_BOAT:90:10", "FLINT:13:2", "LEATHER:56:8", "FEATHER:18:2",
            "STRING:27:4", "BONE:33:5", "ROTTEN_FLESH:0:1", "SPIDER_EYE:33:5",
            "GUNPOWDER:67:10", "SLIME_BALL:90:12", "INK_SAC:33:5", "GLOWSTONE_DUST:45:6",
            "ARROW:13:2", "BUCKET:250:60", "BREAD:67:10", "COOKED_BEEF:90:14",
            "COOKED_PORKCHOP:90:14", "COOKED_CHICKEN:67:10", "COOKED_MUTTON:78:12", "COOKED_RABBIT:67:10",
            "COOKED_SALMON:67:10", "COOKED_COD:56:8", "BAKED_POTATO:56:8", "PUMPKIN_PIE:90:12",
            "COOKIE:18:2", "GOLDEN_CARROT:250:35", "MUSHROOM_STEW:56:8", "HONEY_BOTTLE:90:12",
            "DRIED_KELP:11:1.5", "COAL:33:4", "CHARCOAL:27:3", "RAW_COPPER:33:5",
            "COPPER_INGOT:56:8", "RAW_IRON:90:14", "IRON_INGOT:135:20", "RAW_GOLD:155:24",
            "GOLD_INGOT:225:35", "REDSTONE:33:5", "LAPIS_LAZULI:33:5", "QUARTZ:56:8",
            "EMERALD:250:70", "DIAMOND:250:90", "NETHERITE_SCRAP:250:90", "WOODEN_SWORD:90:11",
            "WOODEN_AXE:135:17", "WOODEN_SPEAR:110:10", "WOODEN_PICKAXE:135:17", "WOODEN_SHOVEL:67:8",
            "WOODEN_HOE:90:12", "STONE_SWORD:135:14", "STONE_AXE:200:22", "STONE_SPEAR:155:14",
            "STONE_PICKAXE:200:22", "STONE_SHOVEL:100:9.5", "STONE_HOE:135:16", "COPPER_SWORD:450:41",
            "COPPER_AXE:675:63", "COPPER_SPEAR:495:44", "COPPER_PICKAXE:675:63", "COPPER_SHOVEL:225:23",
            "COPPER_HOE:450:43", "IRON_SWORD:1350:97", "IRON_AXE:2000:145", "IRON_SPEAR:1450:130",
            "IRON_PICKAXE:2000:145", "IRON_SHOVEL:675:51", "IRON_HOE:1350:99", "GOLDEN_SWORD:1800:160",
            "GOLDEN_AXE:2700:240", "GOLDEN_SPEAR:1900:170", "GOLDEN_PICKAXE:2700:240", "GOLDEN_SHOVEL:900:83",
            "GOLDEN_HOE:1800:160", "DIAMOND_SWORD:5850:400", "DIAMOND_AXE:7200:600", "DIAMOND_SPEAR:6300:560",
            "DIAMOND_PICKAXE:6750:600", "DIAMOND_SHOVEL:5600:200", "DIAMOND_HOE:5600:400", "NETHERITE_SWORD:40500:3600",
            "NETHERITE_AXE:49500:4400", "NETHERITE_SPEAR:42750:3800", "NETHERITE_PICKAXE:49500:4400", "NETHERITE_SHOVEL:36000:3200",
            "NETHERITE_HOE:36000:3200", "BOW:675:33", "CROSSBOW:1000:125", "SHIELD:785:76",
            "TRIDENT:27000:3500", "MACE:135000:15000", "LEATHER_HELMET:200:27", "LEATHER_CHESTPLATE:315:42",
            "LEATHER_LEGGINGS:270:36", "LEATHER_BOOTS:155:21", "COPPER_HELMET:675:90", "COPPER_CHESTPLATE:1050:140",
            "COPPER_LEGGINGS:945:125", "COPPER_BOOTS:540:72", "CHAINMAIL_HELMET:1350:180", "CHAINMAIL_CHESTPLATE:2150:285",
            "CHAINMAIL_LEGGINGS:1850:250", "CHAINMAIL_BOOTS:1050:140", "IRON_HELMET:1550:210", "IRON_CHESTPLATE:2450:330",
            "IRON_LEGGINGS:2100:285", "IRON_BOOTS:1350:180", "GOLDEN_HELMET:2000:270", "GOLDEN_CHESTPLATE:3150:420",
            "GOLDEN_LEGGINGS:2800:375", "GOLDEN_BOOTS:1800:240", "DIAMOND_HELMET:6750:900", "DIAMOND_CHESTPLATE:10800:1400",
            "DIAMOND_LEGGINGS:9450:1250", "DIAMOND_BOOTS:5600:750", "NETHERITE_HELMET:45000:4000", "NETHERITE_CHESTPLATE:72000:6350",
            "NETHERITE_LEGGINGS:63000:5600", "NETHERITE_BOOTS:36000:3200", "TURTLE_HELMET:3350:450", "EXPERIENCE_BOTTLE:225:30",
            "ENDER_PEARL:450:70", "BLAZE_ROD:225:35", "FISHING_ROD:180:20", "SHEARS:270:36",
            "FLINT_AND_STEEL:200:27", "COMPASS:785:90", "CLOCK:1350:150", "BREWING_STAND:560:60",
            "ENCHANTING_TABLE:2700:300", "GOLDEN_APPLE:2250:250", "TOTEM_OF_UNDYING:3000:1", "COAL_BLOCK:0:36",
            "COPPER_BLOCK:0:72", "RAW_COPPER_BLOCK:0:45", "IRON_BLOCK:0:180", "RAW_IRON_BLOCK:0:126",
            "GOLD_BLOCK:0:315", "RAW_GOLD_BLOCK:0:216", "REDSTONE_BLOCK:0:45", "LAPIS_BLOCK:0:45",
            "EMERALD_BLOCK:0:630", "DIAMOND_BLOCK:0:810", "NETHERITE_BLOCK:0:8100", "NETHERITE_INGOT:0:900",
            "ANCIENT_DEBRIS:0:80", "AMETHYST_SHARD:0:40", "ECHO_SHARD:0:3000", "PRISMARINE_SHARD:0:60",
            "PRISMARINE_CRYSTALS:0:80", "PHANTOM_MEMBRANE:0:150", "GHAST_TEAR:0:400", "DRAGON_BREATH:0:1000",
            "NAME_TAG:0:1500", "SADDLE:0:2000", "DISC_FRAGMENT_5:0:2500", "SPONGE:0:3000",
            "NAUTILUS_SHELL:0:5000", "WITHER_ROSE:0:5000", "DRAGON_EGG:0:500", "ELYTRA:0:18000",
            "BEACON:0:150000", "NETHER_STAR:0:120000", "ENCHANTED_GOLDEN_APPLE:0:90000", "DRAGON_HEAD:0:50000",
            "HEAVY_CORE:0:45000", "CONDUIT:0:40000", "HEART_OF_THE_SEA:0:30", "WITHER_SKELETON_SKULL:0:250000",
            "MUSIC_DISC_PIGSTEP:0:18000", "MUSIC_DISC_OTHERSIDE:0:16000", "MUSIC_DISC_5:0:15000", "MUSIC_DISC_RELIC:0:14000",
            "MUSIC_DISC_CREATOR:0:13000", "MUSIC_DISC_PRECIPICE:0:12500", "MUSIC_DISC_CREATOR_MUSIC_BOX:0:12000", "SHULKER_BOX:0:1200",
            "CREEPER_HEAD:0:80000", "PIGLIN_HEAD:0:8000", "MUSIC_DISC_13:0:7000", "MUSIC_DISC_CAT:0:7000",
            "MUSIC_DISC_BLOCKS:0:7000", "MUSIC_DISC_CHIRP:0:7000", "MUSIC_DISC_FAR:0:7000", "MUSIC_DISC_MALL:0:7000",
            "MUSIC_DISC_MELLOHI:0:7000", "MUSIC_DISC_STAL:0:7000", "MUSIC_DISC_STRAD:0:7000", "MUSIC_DISC_WARD:0:7000",
            "MUSIC_DISC_11:0:7000", "MUSIC_DISC_WAIT:0:7000", "SHULKER_SHELL:0:55000", "ZOMBIE_HEAD:0:45000",
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
