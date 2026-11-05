package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /code <code> redeems a code that an admin created.
 * Admin (OP): /code create <code> <money> <points> [maxUses], /code delete, /code list, /code info, /code addcmd
 * Each player can use a code once. Data: plugins/StructureScoreboard/codes.yml
 */
public final class CodeManager implements TabExecutor {

    private static final Set<String> SUBS = Set.of("create", "delete", "list", "info", "addcmd");
    private static final String PURPLE = "&#A855F7";

    private static final class Reward {
        String display;
        double money;
        long points;
        int maxUses; // 0 = unlimited
        String createdBy = "";
        final Set<UUID> usedBy = new LinkedHashSet<>();
        final List<String> commands = new ArrayList<>();
    }

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final PointsManager points;
    private final File file;
    private final Map<String, Reward> codes = new LinkedHashMap<>(); // key = lower-case code
    private final Map<UUID, Long> lastTry = new HashMap<>();

    public CodeManager(JavaPlugin plugin, EconomyManager economy, PointsManager points) {
        this.plugin = plugin;
        this.economy = economy;
        this.points = points;
        this.file = new File(plugin.getDataFolder(), "codes.yml");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("codes");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            Reward r = new Reward();
            r.display = s.getString("name", key);
            r.money = s.getDouble("money");
            r.points = s.getLong("points");
            r.maxUses = s.getInt("max-uses");
            r.createdBy = s.getString("created-by", "");
            for (String id : s.getStringList("used-by")) {
                try {
                    r.usedBy.add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // skip broken entries
                }
            }
            r.commands.addAll(s.getStringList("commands"));
            codes.put(key.toLowerCase(Locale.ROOT), r);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Reward> e : codes.entrySet()) {
            String base = "codes." + e.getKey();
            Reward r = e.getValue();
            yml.set(base + ".name", r.display);
            yml.set(base + ".money", r.money);
            yml.set(base + ".points", r.points);
            yml.set(base + ".max-uses", r.maxUses);
            yml.set(base + ".created-by", r.createdBy);
            List<String> used = new ArrayList<>();
            for (UUID u : r.usedBy) used.add(u.toString());
            yml.set(base + ".used-by", used);
            yml.set(base + ".commands", r.commands);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save codes.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Text.c(PURPLE + "ใช้: &f/code <โค้ด> &7เพื่อแลกรางวัล"));
            if (sender.hasPermission("structuresmp.admin")) {
                sender.sendMessage(Text.c("&7แอดมิน: &f/code create <โค้ด> <เงิน> <พ้อย> [จำนวนครั้ง]"));
                sender.sendMessage(Text.c("&7แอดมิน: &f/code delete|info <โค้ด> &7, &f/code list&7, &f/code addcmd <โค้ด> <คำสั่ง>"));
            }
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (SUBS.contains(first)) {
            admin(sender, first, args);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        redeem(p, args[0]);
        return true;
    }

    private void redeem(Player p, String input) {
        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = lastTry.get(id);
        if (last != null && now - last < 3000L) {
            p.sendMessage(Text.c("&cรอสักครู่ก่อนลองโค้ดอีกครั้ง"));
            return;
        }
        lastTry.put(id, now);

        Reward r = codes.get(input.toLowerCase(Locale.ROOT));
        if (r == null) {
            p.sendMessage(Text.c("&cโค้ดไม่ถูกต้อง"));
            return;
        }
        if (r.usedBy.contains(id)) {
            p.sendMessage(Text.c("&cคุณใช้โค้ดนี้ไปแล้ว"));
            return;
        }
        if (r.maxUses > 0 && r.usedBy.size() >= r.maxUses) {
            p.sendMessage(Text.c("&cโค้ดนี้ถูกใช้ครบจำนวนแล้ว"));
            return;
        }

        r.usedBy.add(id);
        save();

        List<String> got = new ArrayList<>();
        if (r.money > 0) {
            economy.add(id, r.money);
            economy.save();
            got.add("เงิน &e$" + Text.money(r.money));
        }
        if (r.points > 0) {
            points.add(id, r.points);
            points.save();
            got.add("&d" + PointsManager.format(r.points) + " &aพ้อย");
        }
        for (String command : r.commands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", p.getName()));
        }
        if (!r.commands.isEmpty()) got.add("ของรางวัลพิเศษ");

        p.sendMessage(Text.c("&aแลกโค้ด &f" + r.display + " &aสำเร็จ! ได้รับ "
                + (got.isEmpty() ? "-" : String.join("&a, ", got))));
        plugin.getLogger().info(p.getName() + " redeemed code " + r.display);
    }

    // ------------------------------------------------------------------ admin

    private void admin(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission("structuresmp.admin")) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return;
        }
        switch (sub) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "addcmd" -> addCommand(sender, args);
            default -> {
            }
        }
    }

    private void create(CommandSender s, String[] args) {
        if (args.length < 4) {
            s.sendMessage(Text.c("&eใช้: &f/code create <โค้ด> <เงิน> <พ้อย> [จำนวนครั้งที่ใช้ได้]"));
            s.sendMessage(Text.c("&7ตัวอย่าง: &f/code create WELCOME 5000 10 100 &7(ใส่ 0 ถ้าไม่จำกัด)"));
            return;
        }
        String name = args[1];
        String key = name.toLowerCase(Locale.ROOT);
        if (!name.matches("[A-Za-z0-9_-]{3,32}")) {
            s.sendMessage(Text.c("&cชื่อโค้ดใช้ได้เฉพาะ A-Z 0-9 _ - ความยาว 3-32 ตัว"));
            return;
        }
        if (SUBS.contains(key)) {
            s.sendMessage(Text.c("&cชื่อนี้ใช้เป็นโค้ดไม่ได้"));
            return;
        }
        if (codes.containsKey(key)) {
            s.sendMessage(Text.c("&cมีโค้ดนี้อยู่แล้ว (ลบก่อนด้วย /code delete " + name + ")"));
            return;
        }
        double money = Text.parseAmount(args[2]);
        double pts = Text.parseAmount(args[3]);
        if (money < 0 || pts < 0) {
            s.sendMessage(Text.c("&cจำนวนเงินหรือพ้อยไม่ถูกต้อง (พิมพ์เช่น 5000, 2.5k, 1m)"));
            return;
        }
        int maxUses = 0;
        if (args.length > 4) {
            try {
                maxUses = Integer.parseInt(args[4]);
            } catch (NumberFormatException ex) {
                maxUses = -1;
            }
            if (maxUses < 0) {
                s.sendMessage(Text.c("&cจำนวนครั้งที่ใช้ได้ต้องเป็นตัวเลข 0 ขึ้นไป"));
                return;
            }
        }

        Reward r = new Reward();
        r.display = name;
        r.money = money;
        r.points = Math.round(pts);
        r.maxUses = maxUses;
        r.createdBy = s.getName();
        codes.put(key, r);
        save();
        s.sendMessage(Text.c("&aสร้างโค้ด &f" + name + " &aแล้ว: เงิน &e$" + Text.money(money) + " &aพ้อย &d"
                + PointsManager.format(r.points) + " &7(ใช้ได้ " + (maxUses == 0 ? "ไม่จำกัด" : maxUses + " ครั้ง")
                + ", คนละ 1 ครั้ง)"));
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.c("&eใช้: &f/code delete <โค้ด>"));
            return;
        }
        if (codes.remove(args[1].toLowerCase(Locale.ROOT)) == null) {
            s.sendMessage(Text.c("&cไม่พบโค้ดนี้"));
            return;
        }
        save();
        s.sendMessage(Text.c("&aลบโค้ด &f" + args[1] + " &aแล้ว"));
    }

    private void list(CommandSender s) {
        if (codes.isEmpty()) {
            s.sendMessage(Text.c("&7ยังไม่มีโค้ด สร้างด้วย /code create"));
            return;
        }
        s.sendMessage(Text.c(PURPLE + "โค้ดทั้งหมด (" + codes.size() + ")"));
        int shown = 0;
        for (Reward r : codes.values()) {
            if (shown++ >= 30) {
                s.sendMessage(Text.c("&7...และอื่นๆ"));
                break;
            }
            s.sendMessage(Text.c("&f" + r.display + " &7- เงิน $" + Text.money(r.money) + ", พ้อย "
                    + PointsManager.format(r.points) + ", ใช้แล้ว " + r.usedBy.size()
                    + (r.maxUses > 0 ? "/" + r.maxUses : "")));
        }
    }

    private void info(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.c("&eใช้: &f/code info <โค้ด>"));
            return;
        }
        Reward r = codes.get(args[1].toLowerCase(Locale.ROOT));
        if (r == null) {
            s.sendMessage(Text.c("&cไม่พบโค้ดนี้"));
            return;
        }
        s.sendMessage(Text.c(PURPLE + "โค้ด " + r.display));
        s.sendMessage(Text.c("&7เงิน: &e$" + Text.money(r.money) + " &7พ้อย: &d" + PointsManager.format(r.points)));
        s.sendMessage(Text.c("&7ใช้แล้ว: &f" + r.usedBy.size() + (r.maxUses > 0 ? "/" + r.maxUses : " (ไม่จำกัด)")
                + " &7สร้างโดย: &f" + r.createdBy));
        for (String c : r.commands) s.sendMessage(Text.c("&8คำสั่งรางวัล: &7/" + c));
    }

    private void addCommand(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage(Text.c("&eใช้: &f/code addcmd <โค้ด> <คำสั่ง>"));
            s.sendMessage(Text.c("&7ตัวอย่าง: &f/code addcmd WELCOME hammer %player% &7(%player% = ชื่อคนแลก)"));
            return;
        }
        Reward r = codes.get(args[1].toLowerCase(Locale.ROOT));
        if (r == null) {
            s.sendMessage(Text.c("&cไม่พบโค้ดนี้"));
            return;
        }
        String command = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
        if (command.startsWith("/")) command = command.substring(1);
        r.commands.add(command);
        save();
        s.sendMessage(Text.c("&aเพิ่มคำสั่งรางวัลให้โค้ด &f" + r.display + " &a: &7/" + command));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("structuresmp.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(SUBS);
        } else if (args.length == 2 && List.of("delete", "info", "addcmd").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Reward r : codes.values()) out.add(r.display);
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
