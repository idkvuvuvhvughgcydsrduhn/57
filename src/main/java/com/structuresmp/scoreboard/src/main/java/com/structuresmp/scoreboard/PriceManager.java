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

    // ITEM:buy:sell   (0 = cannot be bought / cannot be sold)
    private static final String[] DEFAULTS = {
            "COBBLESTONE:8:2", "STONE:10:3", "DIRT:5:1", "SAND:8:2", "GRAVEL:8:2",
            "NETHERRACK:3:1", "OBSIDIAN:150:40", "GLASS:12:4", "TORCH:5:1", "ARROW:6:2",
            "OAK_LOG:20:6", "SPRUCE_LOG:20:6", "BIRCH_LOG:20:6", "OAK_PLANKS:6:2",
            "COAL:40:12", "COPPER_INGOT:30:10", "IRON_INGOT:100:35", "GOLD_INGOT:150:50",
            "REDSTONE:25:8", "LAPIS_LAZULI:25:8", "QUARTZ:30:10",
            "EMERALD:500:180", "DIAMOND:800:300", "NETHERITE_INGOT:0:4000",
            "WHEAT:15:5", "CARROT:15:5", "POTATO:15:5", "SUGAR_CANE:12:4", "PUMPKIN:25:8",
            "MELON_SLICE:5:1.5", "APPLE:20:6", "BREAD:30:10", "COOKED_BEEF:40:14",
            "COOKED_PORKCHOP:40:14", "GOLDEN_APPLE:500:150",
            "BONE:15:5", "STRING:12:4", "GUNPOWDER:30:10", "SLIME_BALL:40:12",
            "BLAZE_ROD:100:35", "ENDER_PEARL:120:40", "EXPERIENCE_BOTTLE:100:30",
            "ROTTEN_FLESH:0:1"
    };

    private final JavaPlugin plugin;
    private final Map<Material, Double> buy = new LinkedHashMap<>();
    private final Map<Material, Double> sell = new HashMap<>();
    private List<Material> buyList = new ArrayList<>();

    public PriceManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        buy.clear();
        sell.clear();
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) writeDefaults(file);

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null || !m.isItem()) {
                    plugin.getLogger().warning("Unknown item in prices.yml: " + key);
                    continue;
                }
                double b = section.getDouble(key + ".buy", 0);
                double s = section.getDouble(key + ".sell", 0);
                if (b > 0) buy.put(m, b);
                if (s > 0) sell.put(m, s);
            }
        }
        buyList = new ArrayList<>(buy.keySet());
    }

    private void writeDefaults(File file) {
        YamlConfiguration yml = new YamlConfiguration();
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

    public List<Material> buyItems() {
        return buyList;
    }
}
