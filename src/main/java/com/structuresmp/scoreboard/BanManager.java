package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ban types:
 *  /ban <player> [reason]              permanent ban on the name
 *  /tempban <player> <time> [reason]   timed ban on the name (time: 30m, 2h, 1d12h, 1w, 1y)
 *  /ipban <player> [reason]            permanent ban on the player's last known IP
 *  /unban <player>                     removes the name ban and the IP ban of that player's last IP
 *  /banlist, /baninfo <player>
 * Data: plugins/StructureScoreboard/bans.yml
 * OPs cannot be banned.
 */
public final class BanManager implements Listener, CommandExecutor {

    private static final String ADMIN = "structuresmp.admin";
    private static final String FILE = "bans.yml";
    private static final String RED = "&c";
    private static final Pattern PART = Pattern.compile("(\\d+)([smhdwy])");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public record Ban(String target, String reason, String by, long created, long until) {
        boolean permanent() {
            return until < 0;
        }

        boolean expired(long now) {
            return !permanent() && now >= until;
        }
    }

    private final JavaPlugin plugin;
    private final Map<String, Ban> nameBans = new HashMap<>();   // lower-case name -> ban
    private final Map<String, Ban> ipBans = new HashMap<>();     // ip key -> ban
    private final Map<String, String> lastIp = new HashMap<>();  // lower-case name -> last IP seen

    public BanManager(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ------------------------------------------------------------------ storage

    private static String ipKey(String ip) {
        return ip.replace('.', '_').replace(':', '-');
    }

    private File file() {
        return new File(plugin.getDataFolder(), FILE);
    }

    private void load() {
        File f = file();
        YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();

        ConfigurationSection names = y.getConfigurationSection("names");
        if (names != null) {
            for (String key : names.getKeys(false)) {
                ConfigurationSection s = names.getConfigurationSection(key);
                if (s == null) continue;
                nameBans.put(key, read(s, key));
            }
        }
        ConfigurationSection ips = y.getConfigurationSection("ips");
        if (ips != null) {
            for (String key : ips.getKeys(false)) {
                ConfigurationSection s = ips.getConfigurationSection(key);
                if (s == null) continue;
                ipBans.put(key, read(s, s.getString("address", key)));
            }
        }
        ConfigurationSection seen = y.getConfigurationSection("seen");
        if (seen != null) {
            for (String key : seen.getKeys(false)) lastIp.put(key, seen.getString(key, ""));
        }
    }

    private static Ban read(ConfigurationSection s, String target) {
        return new Ban(s.getString("name", target), s.getString("reason", ""), s.getString("by", "?"),
                s.getLong("created"), s.getLong("until", -1));
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Ban> e : nameBans.entrySet()) write(y, "names." + e.getKey(), e.getValue(), null);
        for (Map.Entry<String, Ban> e : ipBans.entrySet()) write(y, "ips." + e.getKey(), e.getValue(), e.getValue().target());
        for (Map.Entry<String, String> e : lastIp.entrySet()) y.set("seen." + e.getKey(), e.getValue());
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file());
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + FILE + ": " + e.getMessage());
        }
    }

    private static void write(YamlConfiguration y, String base, Ban b, String address) {
        y.set(base + ".name", b.target());
        y.set(base + ".reason", b.reason());
        y.set(base + ".by", b.by());
        y.set(base + ".created", b.created());
        y.set(base + ".until", b.until());
        if (address != null) y.set(base + ".address", address);
    }

    // ------------------------------------------------------------------ helpers

    /** Parses 30m, 2h, 1d12h, 1w, 1y. Returns milliseconds, or -1 when invalid. */
    static long parseDuration(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        Matcher m = PART.matcher(s);
        long total = 0;
        int pos = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != pos) return -1;
            pos = m.end();
            any = true;
            long n = Long.parseLong(m.group(1));
            long unit = switch (m.group(2)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                default -> 31_536_000_000L; // y
            };
            total += n * unit;
        }
        return any && pos == s.length() ? total : -1;
    }

    private static String when(long ms) {
        return FMT.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()));
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.length() == 0 ? "ไม่ระบุเหตุผล" : sb.toString();
    }

    private String kickText(Ban b) {
        String until = b.permanent() ? "&4ถาวร" : "&7หมดอายุ: &f" + when(b.until());
        return RED + "&lคุณถูกแบนจาก Structure Smp\n&7เหตุผล: &f" + b.reason() + "\n" + until;
    }

    private boolean isAdmin(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p != null && p.hasPermission(ADMIN);
    }

    // ------------------------------------------------------------------ enforcement

    @EventHandler(priority = EventPriority.LOW)
    public void onLogin(PlayerLoginEvent e) {
        long now = System.currentTimeMillis();
        String name = e.getPlayer().getName().toLowerCase(Locale.ROOT);
        String ip = e.getAddress() == null ? null : e.getAddress().getHostAddress();

        Ban b = nameBans.get(name);
        if (b != null && b.expired(now)) {
            nameBans.remove(name);
            save();
            b = null;
        }
        if (b == null && ip != null) {
            String key = ipKey(ip);
            Ban ib = ipBans.get(key);
            if (ib != null && ib.expired(now)) {
                ipBans.remove(key);
                save();
                ib = null;
            }
            b = ib;
        }
        if (b != null) {
            e.disallow(PlayerLoginEvent.Result.KICK_BANNED, Text.c(kickText(b)));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getAddress() == null) return;
        String key = p.getName().toLowerCase(Locale.ROOT);
        String ip = p.getAddress().getAddress().getHostAddress();
        if (!ip.equals(lastIp.get(key))) {
            lastIp.put(key, ip);
            save();
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "ban" -> {
                if (args.length < 1) usage(sender, "/ban <ผู้เล่น> [เหตุผล]");
                else banName(sender, args[0], join(args, 1), -1);
            }
            case "tempban" -> {
                if (args.length < 2) {
                    usage(sender, "/tempban <ผู้เล่น> <เวลา> [เหตุผล]  (เช่น 30m 2h 1d 1w)");
                    return true;
                }
                long ms = parseDuration(args[1]);
                if (ms <= 0) {
                    sender.sendMessage(Text.c("&cเวลาไม่ถูกต้อง (ใช้ s m h d w y เช่น 30m, 2h, 1d12h)"));
                    return true;
                }
                banName(sender, args[0], join(args, 2), System.currentTimeMillis() + ms);
            }
            case "ipban" -> {
                if (args.length < 1) usage(sender, "/ipban <ผู้เล่น> [เหตุผล]");
                else banIp(sender, args[0], join(args, 1));
            }
            case "unban" -> {
                if (args.length < 1) usage(sender, "/unban <ผู้เล่น>");
                else unban(sender, args[0]);
            }
            case "banlist" -> banlist(sender);
            case "baninfo" -> {
                if (args.length < 1) usage(sender, "/baninfo <ผู้เล่น>");
                else baninfo(sender, args[0]);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void usage(CommandSender s, String text) {
        s.sendMessage(Text.c("&eใช้: &f" + text));
    }

    private void banName(CommandSender s, String name, String reason, long until) {
        if (isAdmin(name)) {
            s.sendMessage(Text.c("&cแบนแอดมินไม่ได้"));
            return;
        }
        Ban b = new Ban(name, reason, s.getName(), System.currentTimeMillis(), until);
        nameBans.put(name.toLowerCase(Locale.ROOT), b);
        save();
        s.sendMessage(Text.c(RED + "แบน &f" + name + RED + " แล้ว (" + (b.permanent() ? "ถาวร" : "ถึง " + when(until))
                + ") เหตุผล: &f" + reason));
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) online.kick(Text.c(kickText(b)));
    }

    private void banIp(CommandSender s, String name, String reason) {
        if (isAdmin(name)) {
            s.sendMessage(Text.c("&cแบนแอดมินไม่ได้"));
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        String ip = online != null && online.getAddress() != null
                ? online.getAddress().getAddress().getHostAddress()
                : lastIp.get(name.toLowerCase(Locale.ROOT));
        if (ip == null || ip.isBlank()) {
            s.sendMessage(Text.c("&cยังไม่รู้ IP ของ " + name + " (ต้องเคยเข้าเซิร์ฟเวอร์ก่อน)"));
            return;
        }
        Ban b = new Ban(ip, reason, s.getName(), System.currentTimeMillis(), -1);
        ipBans.put(ipKey(ip), b);
        save();
        s.sendMessage(Text.c(RED + "แบน IP ของ &f" + name + RED + " แล้ว (ถาวร) &7เหตุผล: &f" + reason));
        s.sendMessage(Text.c("&7หมายเหตุ: ผู้เล่นคนอื่นที่ใช้ IP เดียวกันจะเข้าไม่ได้ด้วย"));
        if (online != null) online.kick(Text.c(kickText(b)));
    }

    private void unban(CommandSender s, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        boolean any = nameBans.remove(key) != null;
        String ip = lastIp.get(key);
        if (ip != null && ipBans.remove(ipKey(ip)) != null) any = true;
        if (!any) {
            s.sendMessage(Text.c("&7ไม่พบการแบนของ &f" + name));
            return;
        }
        save();
        s.sendMessage(Text.c("&aปลดแบน &f" + name + " &aแล้ว"));
    }

    private void banlist(CommandSender s) {
        long now = System.currentTimeMillis();
        s.sendMessage(Text.c(RED + "&l== รายการแบน =="));
        int shown = 0;
        for (Ban b : nameBans.values()) {
            if (b.expired(now)) continue;
            if (shown++ >= 20) break;
            s.sendMessage(Text.c("&7[ชื่อ] &f" + b.target() + " &7- " + (b.permanent() ? "&4ถาวร" : "&6ถึง " + when(b.until()))
                    + " &7- " + b.reason()));
        }
        for (Map.Entry<String, Ban> e : ipBans.entrySet()) {
            if (e.getValue().expired(now)) continue;
            if (shown++ >= 20) break;
            s.sendMessage(Text.c("&7[IP] &f" + e.getValue().target() + " &7- &4ถาวร &7- " + e.getValue().reason()));
        }
        if (shown == 0) s.sendMessage(Text.c("&7ยังไม่มีการแบน"));
    }

    private void baninfo(CommandSender s, String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Ban b = nameBans.get(key);
        String ip = lastIp.get(key);
        Ban ib = ip == null ? null : ipBans.get(ipKey(ip));
        if (b == null && ib == null) {
            s.sendMessage(Text.c("&7" + name + " &7ไม่ถูกแบน" + (ip == null ? "" : " (IP ล่าสุด: " + ip + ")")));
            return;
        }
        if (b != null) {
            s.sendMessage(Text.c(RED + "แบนชื่อ &f" + name + " &7- " + (b.permanent() ? "ถาวร" : "ถึง " + when(b.until()))
                    + " &7- " + b.reason() + " &8(โดย " + b.by() + ")"));
        }
        if (ib != null) {
            s.sendMessage(Text.c(RED + "แบน IP &f" + ip + " &7- ถาวร &7- " + ib.reason() + " &8(โดย " + ib.by() + ")"));
        }
    }
}
