package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small helpers shared by the GUIs. */
public final class Items {

    private Items() {
    }

    /** An item with a colored name and optional lore lines (& color codes). */
    public static ItemStack named(Material m, String name, String... lore) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore(name));
        if (lore.length > 0) {
            List<Component> list = new ArrayList<>();
            for (String line : lore) list.add(Text.lore(line));
            meta.lore(list);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Gives the item to the player; anything that does not fit is dropped at their feet. */
    public static void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    /** True for a real item with no custom name, enchantments, contents, etc. */
    public static boolean isPlain(ItemStack item) {
        return item != null && !item.getType().isAir() && !item.hasItemMeta();
    }
}
