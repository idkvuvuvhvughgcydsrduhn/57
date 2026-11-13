package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Small helpers shared by the menus. */
public final class Gui {

    private Gui() {
    }

    public static ItemStack named(Material m, String name, String... loreLines) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore(name));
        if (loreLines.length > 0) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) lore.add(Text.lore(line));
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** Fills slots 45-53 (bottom row of a 54-slot menu) with blank panes. */
    public static void bottomRow(Inventory inv) {
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s = 45; s < 54; s++) inv.setItem(s, filler);
    }

    public static int pages(int total, int perPage) {
        return Math.max(1, (total + perPage - 1) / perPage);
    }

    public static boolean isArrow(ItemStack item) {
        return item != null && item.getType() == Material.ARROW;
    }

    public static String describe(ItemStack item) {
        return item.getAmount() + "x " + Text.pretty(item.getType());
    }

    /** True when the whole stack would fit in the inventory. */
    public static boolean canFit(Inventory inv, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) return true;
            if (s.isSimilar(item)) remaining -= s.getMaxStackSize() - s.getAmount();
            if (remaining <= 0) return true;
        }
        return false;
    }

    /** Counts plain items (no name/enchants) of a type. */
    public static int countPlain(Inventory inv, Material m) {
        int count = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s != null && s.getType() == m && !s.hasItemMeta()) count += s.getAmount();
        }
        return count;
    }

    public static void removePlain(Inventory inv, Material m, int qty) {
        ItemStack[] contents = inv.getStorageContents();
        int left = qty;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType() != m || s.hasItemMeta()) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take == s.getAmount()) contents[i] = null;
            else s.setAmount(s.getAmount() - take);
        }
        inv.setStorageContents(contents);
    }

    /** Gives items and returns how many actually fit. */
    public static int give(Inventory inv, Material m, int amount) {
        int given = 0;
        while (given < amount) {
            int stack = Math.min(m.getMaxStackSize(), amount - given);
            int notGiven = 0;
            for (ItemStack rest : inv.addItem(new ItemStack(m, stack)).values()) {
                notGiven += rest.getAmount();
            }
            given += stack - notGiven;
            if (notGiven > 0) break;
        }
        return given;
    }
}
