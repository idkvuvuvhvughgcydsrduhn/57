package com.structuresmp.scoreboard;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Buy orders, saved in plugins/StructureScoreboard/orders.yml (saved on every change). */
public final class OrderManager {

    public static final class Order {
        public final int id;
        public final UUID owner;
        public final String ownerName;
        public final Material material;
        public final double price;
        public int amount;
        public int filled;
        public int collected;

        Order(int id, UUID owner, String ownerName, Material material, double price,
              int amount, int filled, int collected) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.material = material;
            this.price = price;
            this.amount = amount;
            this.filled = filled;
            this.collected = collected;
        }

        /** Items still wanted. */
        public int remaining() {
            return amount - filled;
        }

        /** Items delivered but not picked up by the owner yet. */
        public int pending() {
            return filled - collected;
        }

        public boolean done() {
            return remaining() <= 0 && pending() <= 0;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final List<Order> orders = new ArrayList<>();
    private int nextId = 1;

    public OrderManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id", 1);
        ConfigurationSection section = yml.getConfigurationSection("orders");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) continue;
                Material m = Material.matchMaterial(s.getString("material", ""));
                if (m == null) continue;
                orders.add(new Order(
                        Integer.parseInt(key),
                        UUID.fromString(s.getString("owner", "")),
                        s.getString("owner-name", "???"),
                        m,
                        s.getDouble("price"),
                        s.getInt("amount"),
                        s.getInt("filled"),
                        s.getInt("collected")));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Order o : orders) {
            String base = "orders." + o.id;
            yml.set(base + ".owner", o.owner.toString());
            yml.set(base + ".owner-name", o.ownerName);
            yml.set(base + ".material", o.material.name());
            yml.set(base + ".price", o.price);
            yml.set(base + ".amount", o.amount);
            yml.set(base + ".filled", o.filled);
            yml.set(base + ".collected", o.collected);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save orders.yml: " + e.getMessage());
        }
    }

    public Order create(UUID owner, String ownerName, Material material, int amount, double price) {
        Order o = new Order(nextId++, owner, ownerName, material, price, amount, 0, 0);
        orders.add(o);
        save();
        return o;
    }

    public Order find(int id) {
        for (Order o : orders) {
            if (o.id == id) return o;
        }
        return null;
    }

    /** Someone delivered items to the order. */
    public void fill(Order o, int qty) {
        o.filled += qty;
        save();
    }

    /** The owner picked up delivered items. */
    public void collect(Order o, int qty) {
        o.collected += qty;
        if (o.done()) orders.remove(o);
        save();
    }

    /** The owner cancelled the part that was not delivered yet. */
    public void cancel(Order o) {
        o.amount = o.filled;
        if (o.done()) orders.remove(o);
        save();
    }

    /** Orders that still want items (newest first), without the given owner's own orders. */
    public List<Order> open(UUID excludeOwner) {
        List<Order> out = new ArrayList<>();
        for (int i = orders.size() - 1; i >= 0; i--) {
            Order o = orders.get(i);
            if (o.remaining() > 0 && !o.owner.equals(excludeOwner)) out.add(o);
        }
        return out;
    }

    public List<Order> byOwner(UUID owner) {
        List<Order> out = new ArrayList<>();
        for (int i = orders.size() - 1; i >= 0; i--) {
            Order o = orders.get(i);
            if (o.owner.equals(owner)) out.add(o);
        }
        return out;
    }

    public int countByOwner(UUID owner) {
        int n = 0;
        for (Order o : orders) {
            if (o.owner.equals(owner)) n++;
        }
        return n;
    }
}
