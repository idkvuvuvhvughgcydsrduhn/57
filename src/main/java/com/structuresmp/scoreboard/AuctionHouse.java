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

/** Auction house data. Listings are kept in plugins/StructureScoreboard/auctions.yml */
public final class AuctionHouse {

    public static final class Listing {
        public final long id;
        public final UUID seller;
        public final String sellerName;
        public final double price;
        public final ItemStack item;

        Listing(long id, UUID seller, String sellerName, double price, ItemStack item) {
            this.id = id;
            this.seller = seller;
            this.sellerName = sellerName;
            this.price = price;
            this.item = item;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<Long, Listing> listings = new LinkedHashMap<>();
    private long nextId = 1;
    private int maxListings = 5;

    public AuctionHouse(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auctions.yml");
        load();
    }

    public void reload() {
        maxListings = Math.max(1, plugin.getConfig().getInt("ah.max-listings", 5));
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, yml.getLong("next-id", 1));
        ConfigurationSection section = yml.getConfigurationSection("listings");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                long id = Long.parseLong(key);
                ItemStack item = section.getItemStack(key + ".item");
                String seller = section.getString(key + ".seller");
                if (item == null || seller == null) continue;
                listings.put(id, new Listing(id, UUID.fromString(seller),
                        section.getString(key + ".seller-name", "???"),
                        section.getDouble(key + ".price"), item));
                if (id >= nextId) nextId = id + 1;
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("next-id", nextId);
        for (Listing l : listings.values()) {
            String base = "listings." + l.id;
            yml.set(base + ".seller", l.seller.toString());
            yml.set(base + ".seller-name", l.sellerName);
            yml.set(base + ".price", l.price);
            yml.set(base + ".item", l.item);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save auctions.yml: " + e.getMessage());
        }
    }

    public Listing get(long id) {
        return listings.get(id);
    }

    public void remove(long id) {
        if (listings.remove(id) != null) save();
    }

    public int countOf(UUID seller) {
        int n = 0;
        for (Listing l : listings.values()) {
            if (l.seller.equals(seller)) n++;
        }
        return n;
    }

    public List<Listing> newestFirst() {
        List<Listing> list = new ArrayList<>(listings.values());
        list.sort((a, b) -> Long.compare(b.id, a.id));
        return list;
    }

    /** Lists the item in the player's main hand. */
    public void listHand(Player p, double price) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            p.sendMessage(Text.c("&cถือไอเท็มที่จะขายไว้ในมือก่อน"));
            return;
        }
        if (countOf(p.getUniqueId()) >= maxListings) {
            p.sendMessage(Text.c("&cคุณลงขายพร้อมกันได้สูงสุด " + maxListings + " รายการ"));
            return;
        }
        ItemStack copy = hand.clone();
        p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        Listing l = new Listing(nextId++, p.getUniqueId(), p.getName(), price, copy);
        listings.put(l.id, l);
        save();
        p.sendMessage(Text.c("&aลงขาย &f" + Text.pretty(copy.getType()) + " x" + copy.getAmount()
                + " &aราคา &e$" + Text.money(price) + " &7(ดูได้ที่ /ah)"));
    }
}
