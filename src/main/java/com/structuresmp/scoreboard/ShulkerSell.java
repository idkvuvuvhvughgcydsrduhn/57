package com.structuresmp.scoreboard;

import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Sells the items inside a shulker box. The shulker itself is not paid for, only its contents.
 * Contents follow the same rules as /sell: plain items only (no name or enchant), and only items that have a sell price.
 * Sold items are removed from the shulker; anything that cannot be sold stays inside it.
 */
public final class ShulkerSell {

    public record Sold(double total, long count) {
    }

    private ShulkerSell() {
    }

    public static boolean isShulker(ItemStack item) {
        return item != null && !item.getType().isAir() && item.getType().name().endsWith("SHULKER_BOX");
    }

    /** Value of the sellable contents, without changing the shulker. */
    public static Sold peek(ItemStack shulker, PriceManager prices) {
        if (!isShulker(shulker) || !shulker.hasItemMeta()) return new Sold(0, 0);
        if (!(shulker.getItemMeta() instanceof BlockStateMeta bsm)) return new Sold(0, 0);
        if (!(bsm.getBlockState() instanceof ShulkerBox box)) return new Sold(0, 0);
        double total = 0;
        long count = 0;
        for (ItemStack it : box.getInventory().getContents()) {
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            double price = prices.sellPrice(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
        }
        return new Sold(total, count);
    }

    /** Sells the sellable contents and writes the remaining contents back to the shulker item. */
    public static Sold sell(ItemStack shulker, PriceManager prices) {
        if (!isShulker(shulker) || !shulker.hasItemMeta()) return new Sold(0, 0);
        if (!(shulker.getItemMeta() instanceof BlockStateMeta bsm)) return new Sold(0, 0);
        if (!(bsm.getBlockState() instanceof ShulkerBox box)) return new Sold(0, 0);

        Inventory inv = box.getInventory();
        ItemStack[] contents = inv.getContents();
        double total = 0;
        long count = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            double price = prices.sellPrice(it.getType());
            if (price <= 0) continue;
            total += price * it.getAmount();
            count += it.getAmount();
            prices.recordSold(it.getType(), it.getAmount());
            contents[i] = null;
        }
        if (count > 0) {
            inv.setContents(contents);
            bsm.setBlockState(box);
            shulker.setItemMeta(bsm);
        }
        return new Sold(Math.round(total * 100.0) / 100.0, count);
    }
}
