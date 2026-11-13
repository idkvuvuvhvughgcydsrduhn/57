package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Handles /bal /pay /points /baltop /worth /sell /shop /rtp /ah /orders */
public final class Commands implements TabExecutor {

    private final EconomyManager economy;
    private final PointsManager points;
    private final PriceManager prices;
    private final ShopGui shopGui;
    private final SellGui sellGui;
    private final AuctionGui auctionGui;
    private final OrderGui orderGui;
    private final RandomTeleport rtp;

    public Commands(EconomyManager economy, PointsManager points, PriceManager prices, ShopGui shopGui, SellGui sellGui,
                    AuctionGui auctionGui, OrderGui orderGui, RandomTeleport rtp) {
        this.economy = economy;
        this.points = points;
        this.prices = prices;
        this.shopGui = shopGui;
        this.sellGui = sellGui;
        this.auctionGui = auctionGui;
        this.orderGui = orderGui;
        this.rtp = rtp;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "bal" -> cmdBal(sender, args);
            case "pay" -> cmdPay(sender, args);
            case "points" -> cmdPoints(sender, args);
            case "addmoney" -> cmdMoney(sender, args, true);
            case "removemoney" -> cmdMoney(sender, args, false);
            case "addpoint" -> cmdPointAdmin(sender, args, true);
            case "removepoint" -> cmdPointAdmin(sender, args, false);
            case "baltop" -> cmdBaltop(sender);
            case "worth" -> cmdWorth(sender, args);
            case "sell" -> cmdSell(sender, args);
            case "shop" -> cmdShop(sender);
            case "rtp" -> cmdRtp(sender);
            case "ah" -> cmdAh(sender, args);
            case "orders" -> cmdOrders(sender, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (name.equals("worth") && args.length == 1) return List.of("hand");
        if (name.equals("sell") && args.length == 1) return List.of("hand", "all");
        if ((name.equals("bal") || name.equals("pay")) && args.length == 1) return null; // online player names
        if (List.of("addmoney", "removemoney", "addpoint", "removepoint").contains(name)) {
            return args.length == 1 ? null : List.of(); // online player names
        }
        if (name.equals("points")) {
            if (args.length == 1) {
                if (!sender.hasPermission("structuresmp.admin")) return null; // player names
                List<String> out = new ArrayList<>(List.of("give", "take", "set"));
                for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
                String pre = args[0].toLowerCase(Locale.ROOT);
                out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(pre));
                return out;
            }
            if (args.length == 2 && List.of("give", "take", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
                return null; // player names
            }
            return List.of();
        }
        if (name.equals("ah") && args.length == 1) return List.of("sell", "mine");
        if (name.equals("orders")) {
            if (args.length == 1) return List.of("create", "mine");
            if (args.length == 2 && args[0].equalsIgnoreCase("create")) return materialSuggestions(args[1]);
        }
        return List.of();
    }

    private static List<String> materialSuggestions(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if ("hand".startsWith(p)) out.add("hand");
        for (Material m : Material.values()) {
            if (out.size() >= 40) break;
            if (m.isLegacy() || m.isAir() || !m.isItem()) continue;
            String n = m.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(p)) out.add(n);
        }
        return out;
    }

    private void cmdBal(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Usage: /bal <player>");
                return;
            }
            p.sendMessage(Text.c("&eเงินของคุณ: &a$" + Text.money(economy.get(p.getUniqueId()))));
            return;
        }
        UUID id = resolve(args[0]);
        if (id == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[0]));
            return;
        }
        sender.sendMessage(Text.c("&eเงินของ &f" + economy.nameOf(id)
                + "&e: &a$" + Text.money(economy.get(id))));
    }

    /** Finds a player by name: known economy accounts first, then the server's name cache. */
    private UUID resolve(String name) {
        UUID id = economy.findByName(name);
        if (id != null) return id;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null && off.getName() != null) {
            economy.setName(off.getUniqueId(), off.getName());
            return off.getUniqueId();
        }
        return null;
    }

    /** /pay <player> <amount> - anyone can use it. */
    private void cmdPay(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length < 2) {
            p.sendMessage(Text.c("&eใช้: &f/pay <ผู้เล่น> <จำนวนเงิน>"));
            return;
        }
        UUID target = resolve(args[0]);
        if (target == null) {
            p.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[0]));
            return;
        }
        if (target.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cโอนเงินให้ตัวเองไม่ได้"));
            return;
        }
        double amount = Text.parseAmount(args[1]);
        if (amount < 0.01) {
            p.sendMessage(Text.c("&cจำนวนเงินไม่ถูกต้อง (พิมพ์เช่น 500, 2.5k, 1m)"));
            return;
        }
        if (!economy.take(p.getUniqueId(), amount)) {
            p.sendMessage(Text.c("&cเงินไม่พอ คุณมี &a$" + Text.money(economy.get(p.getUniqueId()))));
            return;
        }
        economy.add(target, amount);
        economy.save();

        p.sendMessage(Text.c("&aโอนเงิน &e$" + Text.money(amount) + " &aให้ &f" + economy.nameOf(target)));
        Player receiver = Bukkit.getPlayer(target);
        if (receiver != null) {
            receiver.sendMessage(Text.c("&aคุณได้รับเงิน &e$" + Text.money(amount) + " &aจาก &f" + p.getName()));
        }
    }

    /** /points [player]  and  /points <give|take|set> <player> <amount> (admin) */
    private void cmdPoints(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("give") || sub.equals("take") || sub.equals("set")) {
            if (!sender.hasPermission("structuresmp.admin")) {
                sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้"));
                return;
            }
            if (args.length < 3) {
                sender.sendMessage(Text.c("&eใช้: &f/points " + sub + " <ผู้เล่น> <จำนวน>"));
                return;
            }
            UUID target = resolve(args[1]);
            if (target == null) {
                sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[1]));
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(args[2].replace(",", ""));
            } catch (NumberFormatException ex) {
                amount = -1;
            }
            if (amount < 0) {
                sender.sendMessage(Text.c("&cจำนวนต้องเป็นตัวเลขตั้งแต่ 0 ขึ้นไป"));
                return;
            }
            if (sub.equals("give")) points.add(target, amount);
            else if (sub.equals("take")) points.set(target, points.get(target) - amount);
            else points.set(target, amount);
            points.save();

            sender.sendMessage(Text.c("&aพ้อยของ &f" + economy.nameOf(target) + " &aตอนนี้: &d"
                    + PointsManager.format(points.get(target))));
            Player online = Bukkit.getPlayer(target);
            if (online != null && sub.equals("give") && amount > 0) {
                online.sendMessage(Text.c("&dคุณได้รับ &f" + PointsManager.format(amount) + " &dพ้อย"));
            }
            return;
        }

        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Usage: /points <player>");
                return;
            }
            p.sendMessage(Text.c("&dพ้อยของคุณ: &f" + PointsManager.format(points.get(p.getUniqueId()))));
            return;
        }
        UUID id = resolve(args[0]);
        if (id == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[0]));
            return;
        }
        sender.sendMessage(Text.c("&dพ้อยของ &f" + economy.nameOf(id) + "&d: &f"
                + PointsManager.format(points.get(id))));
    }

    /** /addmoney and /removemoney <player> <amount> - OP only. Amounts accept 500, 2.5k, 1m. */
    private void cmdMoney(CommandSender sender, String[] args, boolean add) {
        if (!sender.hasPermission("structuresmp.admin")) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return;
        }
        String cmd = add ? "addmoney" : "removemoney";
        if (args.length < 2) {
            sender.sendMessage(Text.c("&eใช้: &f/" + cmd + " <ผู้เล่น> <จำนวนเงิน>"));
            return;
        }
        UUID target = resolve(args[0]);
        if (target == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[0]));
            return;
        }
        double amount = Text.parseAmount(args[1]);
        if (amount < 0.01 || amount > 1_000_000_000_000_000d) {
            sender.sendMessage(Text.c("&cจำนวนเงินไม่ถูกต้อง (พิมพ์เช่น 500, 2.5k, 1m)"));
            return;
        }

        String name = economy.nameOf(target);
        Player online = Bukkit.getPlayer(target);
        if (add) {
            economy.add(target, amount);
            sender.sendMessage(Text.c("&aเพิ่มเงินให้ &f" + name + " &e$" + Text.money(amount)
                    + " &7(ตอนนี้ $" + Text.money(economy.get(target)) + ")"));
            if (online != null) online.sendMessage(Text.c("&aคุณได้รับเงิน &e$" + Text.money(amount)));
        } else {
            double current = economy.get(target);
            double removed = Math.min(current, amount);
            economy.set(target, current - removed);
            sender.sendMessage(Text.c("&aลบเงินของ &f" + name + " &e$" + Text.money(removed)
                    + " &7(ตอนนี้ $" + Text.money(economy.get(target)) + ")"));
            if (online != null) online.sendMessage(Text.c("&cเงินของคุณถูกหัก &e$" + Text.money(removed)));
        }
        economy.save();
    }

    /** /addpoint and /removepoint <player> <amount> - OP only. */
    private void cmdPointAdmin(CommandSender sender, String[] args, boolean add) {
        if (!sender.hasPermission("structuresmp.admin")) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return;
        }
        String cmd = add ? "addpoint" : "removepoint";
        if (args.length < 2) {
            sender.sendMessage(Text.c("&eใช้: &f/" + cmd + " <ผู้เล่น> <จำนวนพ้อย>"));
            return;
        }
        UUID target = resolve(args[0]);
        if (target == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[0]));
            return;
        }
        double parsed = Text.parseAmount(args[1]);
        if (parsed < 1 || parsed > 1_000_000_000_000_000d) {
            sender.sendMessage(Text.c("&cจำนวนพ้อยไม่ถูกต้อง (ตัวเลขตั้งแต่ 1 ขึ้นไป)"));
            return;
        }
        long amount = Math.round(parsed);

        String name = economy.nameOf(target);
        Player online = Bukkit.getPlayer(target);
        if (add) {
            points.add(target, amount);
            sender.sendMessage(Text.c("&aเพิ่มพ้อยให้ &f" + name + " &d" + PointsManager.format(amount)
                    + " &7(ตอนนี้ " + PointsManager.format(points.get(target)) + ")"));
            if (online != null) {
                online.sendMessage(Text.c("&dคุณได้รับ &f" + PointsManager.format(amount) + " &dพ้อย"));
            }
        } else {
            long removed = Math.min(points.get(target), amount);
            points.set(target, points.get(target) - removed);
            sender.sendMessage(Text.c("&aลบพ้อยของ &f" + name + " &d" + PointsManager.format(removed)
                    + " &7(ตอนนี้ " + PointsManager.format(points.get(target)) + ")"));
            if (online != null) {
                online.sendMessage(Text.c("&cพ้อยของคุณถูกหัก &f" + PointsManager.format(removed)));
            }
        }
        points.save();
    }

    private void cmdBaltop(CommandSender sender) {
        List<Map.Entry<UUID, Double>> top = economy.top(10);
        sender.sendMessage(Text.c("&6&l== อันดับคนรวยที่สุด =="));
        if (top.isEmpty()) {
            sender.sendMessage(Text.c("&7ยังไม่มีข้อมูล"));
            return;
        }
        int rank = 1;
        for (Map.Entry<UUID, Double> e : top) {
            sender.sendMessage(Text.c("&e" + rank + ". &f" + economy.nameOf(e.getKey())
                    + " &7- &a$" + Text.money(e.getValue())));
            rank++;
        }
    }

    private void cmdWorth(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length == 0) {
            prices.worthGui().open(p, 0); // full list of sellable items
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            p.sendMessage(Text.c("&cถือไอเท็มที่อยากดูราคาไว้ในมือก่อน"));
            return;
        }
        Material m = item.getType();
        double sell = prices.sellPrice(m);
        double buy = prices.buyPrice(m);
        if (sell <= 0 && buy <= 0) {
            p.sendMessage(Text.c("&cไอเท็มนี้ไม่มีราคา"));
            return;
        }
        p.sendMessage(Text.c("&eไอเท็ม: &f" + Text.pretty(m)));
        if (sell > 0) {
            p.sendMessage(Text.c("&eราคาขาย: &a$" + Text.money(sell) + " &7/ ชิ้น &8(ทั้งกอง $"
                    + Text.money(sell * item.getAmount()) + ")"));
            if (item.hasItemMeta()) {
                p.sendMessage(Text.c("&7ไอเท็มที่มีชื่อหรือเอนชานต์ ขายไม่ได้"));
            }
        }
        if (buy > 0) {
            p.sendMessage(Text.c("&eราคาซื้อ: &a$" + Text.money(buy) + " &7/ ชิ้น"));
        }
    }

    private void cmdSell(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length == 0) {
            sellGui.open(p);
            return;
        }
        String mode = args[0].toLowerCase(Locale.ROOT);
        UUID id = p.getUniqueId();
        PlayerInventory inv = p.getInventory();

        if (mode.equals("hand")) {
            ItemStack item = inv.getItemInMainHand();
            double price = item.getType().isAir() ? 0 : prices.sellPrice(item.getType());
            if (price <= 0 || item.hasItemMeta()) {
                p.sendMessage(Text.c("&cขายไอเท็มในมือไม่ได้"));
                return;
            }
            double total = price * item.getAmount();
            int amount = item.getAmount();
            Material m = item.getType();
            inv.setItemInMainHand(new ItemStack(Material.AIR));
            economy.add(id, total);
            p.sendMessage(Text.c("&aขาย &f" + Text.pretty(m) + " x" + amount
                    + " &aได้เงิน &e$" + Text.money(total)));
            return;
        }

        if (mode.equals("all")) {
            ItemStack[] contents = inv.getStorageContents();
            double total = 0;
            int count = 0;
            for (int i = 0; i < contents.length; i++) {
                ItemStack item = contents[i];
                if (item == null || item.getType().isAir() || item.hasItemMeta()) continue;
                double price = prices.sellPrice(item.getType());
                if (price <= 0) continue;
                total += price * item.getAmount();
                count += item.getAmount();
                contents[i] = null;
            }
            if (count == 0) {
                p.sendMessage(Text.c("&cไม่มีไอเท็มที่ขายได้ในกระเป๋า"));
                return;
            }
            inv.setStorageContents(contents);
            economy.add(id, total);
            p.sendMessage(Text.c("&aขายของ &f" + count + " &aชิ้น ได้เงิน &e$" + Text.money(total)));
            return;
        }

        p.sendMessage(Text.c("&eใช้: &f/sell &7(เปิดเมนู) &f/sell hand &7หรือ &f/sell all"));
    }

    private void cmdShop(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        shopGui.open(p, 0);
    }

    private void cmdRtp(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        rtp.start(p);
    }

    private void cmdAh(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length == 0) {
            auctionGui.open(p, 0, false);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell" -> {
                if (args.length < 2) p.sendMessage(Text.c("&eใช้: &f/ah sell <ราคา>"));
                else auctionGui.createListing(p, args[1]);
            }
            case "mine", "my" -> auctionGui.open(p, 0, true);
            default -> p.sendMessage(Text.c("&eใช้: &f/ah &7| &f/ah sell <ราคา> &7| &f/ah mine"));
        }
    }

    private void cmdOrders(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length == 0) {
            orderGui.open(p, 0, false);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 4) {
                    p.sendMessage(Text.c("&eใช้: &f/orders create <ไอเท็ม> <จำนวน> <ราคาต่อชิ้น>"));
                } else {
                    orderGui.create(p, args[1], args[2], args[3]);
                }
            }
            case "mine", "my" -> orderGui.open(p, 0, true);
            default -> p.sendMessage(Text.c("&eใช้: &f/orders &7| &f/orders create <ไอเท็ม> <จำนวน> <ราคา> &7| &f/orders mine"));
        }
    }
}
