package com.structuresmp.scoreboard;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Buy orders. Stored in plugins/StructureScoreboard/orders.yml */
public final class OrderBook {

    public static final class Order {
        public final long id;
        public final UUID owner;
        public final String ownerName;
        public final Material material;
        public final double pricePerItem;
        public final int total;
        public int remaining;  // still wanted
        public int delivered;  // delivered by others, waiting for the owner to collect

        Order(long id, UUID owner, String ownerName, Material material,
              double pricePerItem, int total, int remaining, int delivered) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.material = material;
            this.pricePerItem = pricePerItem;
            this.total = total;
            this.remaining = remaining;
            this.delivered = delivered;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private long nextId = 1;
    private int maxPerPlayer = 5;

    public OrderBook(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "orders.yml");
        load();
    }

    public void reload() {
        maxPerPlayer = Math.max(1, plugin.getConfig().getInt("orders.max-per-player", 5));
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, yml.getLong("next-id", 1));
        ConfigurationSection section = yml.getConfigurationSection("orders");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                Material m = Material.matchMaterial(section.getString(key + ".material", ""));
                String owner = section.getString(key + ".owner");
                if (m == null || owner == null) continue;
                orders.put(id, new Order(id, UUID.fromString(owner),
                        section.getString(key + ".owner-name", "???"), m,
                        section.getDouble(key + ".price"), section.getInt(key + ".total"),
                        section.getInt(key + ".remaining"), section.getInt(key + ".delivered")));
                if (id >= nextId) nextId = id + 1;
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Order o : orders.values()) {
            String base = "orders." + o.id;
            yml.set(base + ".owner", o.owner.toString());
            yml.set(base + ".owner-name", o.ownerName);
            yml.set(base + ".material", o.material.name());
            yml.set(base + ".price", o.pricePerItem);
            yml.set(base + ".total", o.total);
            yml.set(base + ".remaining", o.remaining);
            yml.set(base + ".delivered", o.delivered);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save orders.yml: " + e.getMessage());
        }
    }

    public Order get(long id) {
        return orders.get(id);
    }

    public int countOf(UUID owner) {
        int n = 0;
        for (Order o : orders.values()) {
            if (o.owner.equals(owner)) n++;
        }
        return n;
    }

    public List<Order> newestFirst() {
        List<Order> list = new ArrayList<>(orders.values());
        list.sort((a, b) -> Long.compare(b.id, a.id));
        return list;
    }

    /** The owner must already have paid amount * pricePerItem. */
    public Order create(Player owner, Material material, int amount, double pricePerItem) {
        Order o = new Order(nextId++, owner.getUniqueId(), owner.getName(), material,
                pricePerItem, amount, amount, 0);
        orders.put(o.id, o);
        save();
        return o;
    }

    /** Records delivered items and returns the money the deliverer earns. */
    public double deliver(Order o, int amount) {
        o.remaining -= amount;
        o.delivered += amount;
        save();
        return Math.round(amount * o.pricePerItem * 100.0) / 100.0;
    }

    /** Gives the waiting items to the owner. Returns how many fit in the inventory. */
    public int collect(Player p, Order o) {
        int amount = o.delivered;
        if (amount <= 0) return 0;
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(o.material, amount));
        int notGiven = 0;
        for (ItemStack it : left.values()) notGiven += it.getAmount();
        int given = amount - notGiven;
        o.delivered -= given;
        cleanup(o);
        save();
        return given;
    }

    /** Stops the order and returns the money for what is still not delivered. */
    public double cancel(Order o) {
        double refund = Math.round(o.remaining * o.pricePerItem * 100.0) / 100.0;
        o.remaining = 0;
        cleanup(o);
        save();
        return refund;
    }

    private void cleanup(Order o) {
        if (o.remaining <= 0 && o.delivered <= 0) orders.remove(o.id);
    }
}
