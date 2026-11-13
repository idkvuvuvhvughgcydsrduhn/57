package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Built-in economy. Balances are kept in plugins/StructureScoreboard/balances.yml */
public final class EconomyManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Double> balances = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private double startingBalance = 1000;
    private boolean dirty;
    private boolean trusted = true;

    public EconomyManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
        load();
    }

    public void setStartingBalance(double value) {
        this.startingBalance = value;
    }

    private void load() {
        SafeYaml.Result r = SafeYaml.load(plugin, "balances.yml");
        trusted = r.trusted;
        YamlConfiguration yml = r.yml;
        ConfigurationSection section = yml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                balances.put(id, section.getDouble(key + ".balance"));
                String name = section.getString(key + ".name");
                if (name != null) names.put(id, name);
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    public void save() {
        if (!dirty || !trusted) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : balances.entrySet()) {
            String base = "players." + e.getKey();
            yml.set(base + ".balance", e.getValue());
            String name = names.get(e.getKey());
            if (name != null) yml.set(base + ".name", name);
        }
        if (SafeYaml.write(plugin, "balances.yml", yml)) dirty = false;
    }

    public double get(UUID id) {
        Double value = balances.get(id);
        if (value == null) {
            value = startingBalance;
            balances.put(id, value);
            dirty = true;
        }
        return value;
    }

    public void set(UUID id, double amount) {
        balances.put(id, round(amount));
        dirty = true;
    }

    public void add(UUID id, double amount) {
        set(id, get(id) + amount);
    }

    /** Takes money if the player has enough. Returns false when they do not. */
    public boolean take(UUID id, double amount) {
        double current = get(id);
        if (current + 0.000001 < amount) return false;
        set(id, Math.max(0, current - amount));
        return true;
    }

    public void setName(UUID id, String name) {
        String previous = names.put(id, name);
        if (!name.equals(previous)) dirty = true;
    }

    public UUID findByName(String name) {
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    public String nameOf(UUID id) {
        String name = names.get(id);
        if (name == null) {
            OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
            name = offline.getName();
        }
        return name == null ? "???" : name;
    }

    public List<Map.Entry<UUID, Double>> top(int limit) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(balances.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
