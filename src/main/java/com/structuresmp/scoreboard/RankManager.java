package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ranks. Given with /rank set <player> <rank> (OP).
 * The rank shows above the head and in the TAB list (as a scoreboard-team prefix, which also sorts the
 * TAB list by rank), and on the sidebar with the %rank% placeholder.
 * Each rank has its own home limit (max-homes, -1 = unlimited).
 * Ranks are defined in config.yml under "ranks"; assignments are saved in ranks.yml.
 */
public final class RankManager implements Listener, TabExecutor {

    public record Rank(String id, String display, String color, int weight, int maxHomes, int order) {
    }

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final File file;
    private final Map<UUID, String> assigned = new HashMap<>();
    private final Map<String, Rank> ranks = new LinkedHashMap<>(); // highest weight first
    private String defaultId = "member";
    private String prefixFormat = "%color%&l[%display%]&r ";
    private BukkitTask task;

    public RankManager(JavaPlugin plugin, EconomyManager economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "ranks.yml");
        load();
    }

    // ------------------------------------------------------------------ config

    private void defaultRank(String id, String display, String color, int weight, int maxHomes) {
        String base = "ranks.list." + id + ".";
        plugin.getConfig().addDefault(base + "display", display);
        plugin.getConfig().addDefault(base + "color", color);
        plugin.getConfig().addDefault(base + "weight", weight);
        plugin.getConfig().addDefault(base + "max-homes", maxHomes);
    }

    /** Plain colour codes, no hex and no bold, so every mobile Java client draws the prefix correctly. */
    private static final Map<String, String> LEGACY_COLORS = Map.of(
            "owner", "&6", "co-owner", "&e", "admin", "&c", "staff", "&9",
            "media", "&d", "supporter", "&a", "member", "&7");

    public void reload() {
        // one-time style migration: replace hex colours and bold prefixes with legacy codes
        var raw = plugin.getConfig();
        if (raw.getInt("ranks.style-version", 1) < 2) {
            raw.set("ranks.prefix-format", "%color%[%display%]&r ");
            for (Map.Entry<String, String> e : LEGACY_COLORS.entrySet()) {
                raw.set("ranks.list." + e.getKey() + ".color", e.getValue());
            }
            raw.set("ranks.style-version", 2);
            plugin.saveConfig();
        }

        plugin.getConfig().addDefault("ranks.default", "member");
        plugin.getConfig().addDefault("ranks.prefix-format", "%color%[%display%]&r ");
        defaultRank("owner", "Owner", "&6", 100, -1);
        defaultRank("co-owner", "Co-Owner", "&e", 90, 30);
        defaultRank("admin", "Admin", "&c", 80, 20);
        defaultRank("staff", "Staff", "&9", 60, 12);
        defaultRank("media", "Media", "&d", 40, 8);
        defaultRank("supporter", "Supporter", "&a", 30, 6);
        defaultRank("member", "Member", "&7", 10, 3);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        prefixFormat = cfg.getString("ranks.prefix-format", "%color%[%display%]&r ");

        List<Rank> loaded = new ArrayList<>();
        ConfigurationSection list = cfg.getConfigurationSection("ranks.list");
        if (list != null) {
            for (String id : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(id);
                if (s == null) continue;
                loaded.add(new Rank(id.toLowerCase(Locale.ROOT), s.getString("display", id),
                        s.getString("color", "&f"), s.getInt("weight", 0), s.getInt("max-homes", 3), 0));
            }
        }
        if (loaded.isEmpty()) loaded.add(new Rank("member", "Member", "&7", 10, 3, 0));
        loaded.sort((a, b) -> Integer.compare(b.weight(), a.weight()));

        ranks.clear();
        int order = 0;
        for (Rank r : loaded) {
            ranks.put(r.id(), new Rank(r.id(), r.display(), r.color(), r.weight(), r.maxHomes(), order++));
        }
        String wanted = cfg.getString("ranks.default", "member").toLowerCase(Locale.ROOT);
        defaultId = ranks.containsKey(wanted) ? wanted : loaded.get(loaded.size() - 1).id(); // lowest rank

        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, 20L);
        refreshAll();
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    // ------------------------------------------------------------------ queries (used by the scoreboard and homes)

    public Rank rankOf(UUID id) {
        String rankId = assigned.get(id);
        Rank r = rankId == null ? null : ranks.get(rankId);
        return r != null ? r : ranks.get(defaultId);
    }

    public Rank rankOf(Player p) {
        return rankOf(p.getUniqueId());
    }

    /** Coloured rank name for the scoreboard, e.g. "&#FFD54AOwner". */
    public String coloredName(Player p) {
        Rank r = rankOf(p);
        return r.color() + r.display();
    }

    public String prefixOf(Rank r) {
        return prefixFormat.replace("%color%", r.color()).replace("%display%", r.display());
    }

    /** How many homes this player may have. */
    public int maxHomes(Player p) {
        int n = rankOf(p).maxHomes();
        return n < 0 ? Integer.MAX_VALUE : n;
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                assigned.put(UUID.fromString(key), section.getString(key, "").toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : assigned.entrySet()) {
            yml.set("players." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save ranks.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ name tags + TAB list

    private String teamName(Rank r) {
        String slug = r.id().replaceAll("[^a-z0-9]", "");
        if (slug.length() > 8) slug = slug.substring(0, 8);
        // the TAB list is sorted by team name, so the number keeps the highest rank on top
        return "rk" + String.format("%02d", Math.min(r.order(), 99)) + slug;
    }

    /** Puts every online player into the team of their rank on one scoreboard. */
    private void applyTo(Scoreboard sb) {
        for (Player t : Bukkit.getOnlinePlayers()) {
            Rank r = rankOf(t);
            String name = teamName(r);
            Team team = sb.getTeam(name);
            if (team == null) team = sb.registerNewTeam(name);

            Component prefix = Text.c(prefixOf(r));
            if (!prefix.equals(team.prefix())) team.prefix(prefix);

            String entry = t.getName();
            Team current = sb.getEntryTeam(entry);
            if (current != null && current.getName().equals(name)) continue;
            if (current != null) {
                if (!current.getName().startsWith("rk")) continue; // belongs to another plugin's team: leave it
                current.removeEntry(entry);
            }
            team.addEntry(entry);
        }
    }

    /** Every viewer (our sidebar board or the main scoreboard) gets the rank teams. */
    public void refreshAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyTo(viewer.getScoreboard());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, this::refreshAll, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        String entry = e.getPlayer().getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Team team = viewer.getScoreboard().getEntryTeam(entry);
            if (team != null && team.getName().startsWith("rk")) team.removeEntry(entry);
        }
    }

    // ------------------------------------------------------------------ commands

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

    private boolean isAdmin(CommandSender s) {
        if (s.hasPermission("structuresmp.admin")) return true;
        s.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("setrank")) {
            if (isAdmin(sender)) {
                if (args.length < 2) sender.sendMessage(Text.c("&eใช้: &f/setrank <ผู้เล่น> <ยศ>"));
                else setRank(sender, args[0], args[1]);
            }
            return true;
        }

        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> list(sender);
            case "info" -> {
                if (args.length < 2) {
                    sender.sendMessage(Text.c("&eใช้: &f/rank info <ผู้เล่น>"));
                    return true;
                }
                UUID id = resolve(args[1]);
                if (id == null) {
                    sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[1]));
                    return true;
                }
                Rank r = rankOf(id);
                sender.sendMessage(Text.c("&7ยศของ &f" + economy.nameOf(id) + "&7: " + r.color() + r.display()
                        + " &7(บ้านสูงสุด " + (r.maxHomes() < 0 ? "ไม่จำกัด" : r.maxHomes()) + ")"));
            }
            case "set" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 3) sender.sendMessage(Text.c("&eใช้: &f/rank set <ผู้เล่น> <ยศ>"));
                else setRank(sender, args[1], args[2]);
            }
            case "remove" -> {
                if (!isAdmin(sender)) return true;
                if (args.length < 2) {
                    sender.sendMessage(Text.c("&eใช้: &f/rank remove <ผู้เล่น>"));
                    return true;
                }
                UUID id = resolve(args[1]);
                if (id == null) {
                    sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[1]));
                    return true;
                }
                assigned.remove(id);
                save();
                refreshAll();
                sender.sendMessage(Text.c("&aรีเซ็ตยศของ &f" + economy.nameOf(id) + " &aกลับเป็นยศเริ่มต้นแล้ว"));
            }
            case "homes" -> {
                if (!isAdmin(sender)) return true;
                setHomes(sender, args);
            }
            default -> sender.sendMessage(Text.c("&eใช้: &f/rank &7| &f/rank info <ผู้เล่น> &7| &f/rank set <ผู้เล่น> <ยศ> "
                    + "&7| &f/rank remove <ผู้เล่น> &7| &f/rank homes <ยศ> <จำนวน>"));
        }
        return true;
    }

    private void list(CommandSender sender) {
        sender.sendMessage(Text.c("&#A855F7&lยศทั้งหมด"));
        for (Rank r : ranks.values()) {
            sender.sendMessage(Text.c(prefixOf(r) + "&7id: &f" + r.id() + " &7| บ้านสูงสุด: &f"
                    + (r.maxHomes() < 0 ? "ไม่จำกัด" : r.maxHomes())));
        }
        if (sender instanceof Player p) {
            Rank mine = rankOf(p);
            sender.sendMessage(Text.c("&7ยศของคุณ: " + mine.color() + mine.display()));
        }
    }

    private void setRank(CommandSender sender, String playerName, String rankName) {
        UUID target = resolve(playerName);
        if (target == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + playerName));
            return;
        }
        Rank r = ranks.get(rankName.toLowerCase(Locale.ROOT));
        if (r == null) {
            sender.sendMessage(Text.c("&cไม่มียศชื่อ " + rankName + " &7(ยศที่มี: " + String.join(", ", ranks.keySet()) + ")"));
            return;
        }
        assigned.put(target, r.id());
        save();
        refreshAll();
        sender.sendMessage(Text.c("&aตั้งยศของ &f" + economy.nameOf(target) + " &aเป็น " + r.color() + r.display()));
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            online.sendMessage(Text.c("&#A855F7ยศของคุณถูกตั้งเป็น " + r.color() + r.display()));
        }
    }

    private void setHomes(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.c("&eใช้: &f/rank homes <ยศ> <จำนวน> &7(ใส่ -1 ถ้าไม่จำกัด)"));
            return;
        }
        Rank r = ranks.get(args[1].toLowerCase(Locale.ROOT));
        if (r == null) {
            sender.sendMessage(Text.c("&cไม่มียศชื่อ " + args[1]));
            return;
        }
        int n;
        try {
            n = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            sender.sendMessage(Text.c("&cจำนวนต้องเป็นตัวเลข (ใส่ -1 ถ้าไม่จำกัด)"));
            return;
        }
        if (n < -1) n = -1;
        plugin.getConfig().set("ranks.list." + r.id() + ".max-homes", n);
        plugin.saveConfig();
        reload();
        sender.sendMessage(Text.c("&aยศ " + r.color() + r.display() + " &aตั้งบ้านได้สูงสุด &f"
                + (n < 0 ? "ไม่จำกัด" : n) + " &aหลัง"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        boolean admin = sender.hasPermission("structuresmp.admin");
        List<String> out = new ArrayList<>();
        if (cmd.getName().equalsIgnoreCase("setrank")) {
            if (!admin) return List.of();
            if (args.length == 1) addPlayers(out);
            else if (args.length == 2) out.addAll(ranks.keySet());
            else return List.of();
        } else if (args.length == 1) {
            out.add("list");
            out.add("info");
            if (admin) out.addAll(List.of("set", "remove", "homes"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("info") || (admin && (sub.equals("set") || sub.equals("remove")))) addPlayers(out);
            else if (admin && sub.equals("homes")) out.addAll(ranks.keySet());
            else return List.of();
        } else if (args.length == 3 && admin && args[0].equalsIgnoreCase("set")) {
            out.addAll(ranks.keySet());
        } else {
            return List.of();
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }

    private void addPlayers(List<String> out) {
        for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
    }
}
