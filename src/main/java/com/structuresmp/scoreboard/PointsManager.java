package com.structuresmp.scoreboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Player points, saved in plugins/StructureScoreboard/points.yml */
public final class PointsManager {

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Long> points = new HashMap<>();
    private boolean dirty;
    private boolean trusted = true;

    public PointsManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "points.yml");
        load();
    }

    private void load() {
        SafeYaml.Result r = SafeYaml.load(plugin, "points.yml");
        trusted = r.trusted;
        YamlConfiguration yml = r.yml;
        ConfigurationSection section = yml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                points.put(UUID.fromString(key), section.getLong(key));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    public void save() {
        if (!dirty || !trusted) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Long> e : points.entrySet()) {
            yml.set("players." + e.getKey(), e.getValue());
        }
        if (SafeYaml.write(plugin, "points.yml", yml)) dirty = false;
    }

    public long get(UUID id) {
        return points.getOrDefault(id, 0L);
    }

    public void set(UUID id, long value) {
        points.put(id, Math.max(0L, value));
        dirty = true;
    }

    public void add(UUID id, long amount) {
        long sum;
        try {
            sum = Math.addExact(get(id), amount);
        } catch (ArithmeticException e) {
            sum = Long.MAX_VALUE;
        }
        set(id, sum);
    }

    public static String format(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
