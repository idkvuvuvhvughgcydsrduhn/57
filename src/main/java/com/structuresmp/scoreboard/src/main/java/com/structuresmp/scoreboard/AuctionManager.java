package com.structuresmp.scoreboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Auction house listings, saved in plugins/StructureScoreboard/auctions.yml (saved on every change). */
public final class AuctionManager {

    public static final class Listing {
        public final int id;
        public final UUID seller;
        public final String sellerName;
        public final double price;
        public final long expiresAt;
        public final ItemStack item;

        Listing(int id, UUID seller, String sellerName, double price, long expiresAt, ItemStack item) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.price = price;
            this.expiresAt = expiresAt;
            this.item = item;
        }

        public boolean expired(long now) {
            return now >= expiresAt;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final List<Listing> listings = new ArrayList<>();
    private int nextId = 1;

    public AuctionManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = yml.getInt("next-id", 1);
        ConfigurationSection section = yml.getConfigurationSection("listings");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) continue;
                ItemStack item = s.getItemStack("item");
                if (item == null) continue;
                listings.add(new Listing(
                        Integer.parseInt(key),
                        UUID.fromString(s.getString("seller", "")),
                        s.getString("seller-name", "???"),
                        s.getDouble("price"),
                        s.getLong("expires-at"),
                        item));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Listing l : listings) {
            String base = "listings." + l.id;
            yml.set(base + ".seller", l.seller.toString());
            yml.set(base + ".seller-name", l.sellerName);
            yml.set(base + ".price", l.price);
            yml.set(base + ".expires-at", l.expiresAt);
            yml.set(base + ".item", l.item);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save auctions.yml: " + e.getMessage());
        }
    }

    public Listing create(UUID seller, String sellerName, double price, long expiresAt, ItemStack item) {
        Listing l = new Listing(nextId++, seller, sellerName, price, expiresAt, item);
        listings.add(l);
        save();
        return l;
    }

    public Listing find(int id) {
        for (Listing l : listings) {
            if (l.id == id) return l;
        }
        return null;
    }

    public void remove(Listing l) {
        listings.remove(l);
        save();
    }

    /** Listings that can still be bought, newest first. */
    public List<Listing> active(long now) {
        List<Listing> out = new ArrayList<>();
        for (int i = listings.size() - 1; i >= 0; i--) {
            Listing l = listings.get(i);
            if (!l.expired(now)) out.add(l);
        }
        return out;
    }

    /** Everything a player listed (active and expired), newest first. */
    public List<Listing> byOwner(UUID owner) {
        List<Listing> out = new ArrayList<>();
        for (int i = listings.size() - 1; i >= 0; i--) {
            Listing l = listings.get(i);
            if (l.seller.equals(owner)) out.add(l);
        }
        return out;
    }

    public int countByOwner(UUID owner) {
        int n = 0;
        for (Listing l : listings) {
            if (l.seller.equals(owner)) n++;
        }
        return n;
    }
}
