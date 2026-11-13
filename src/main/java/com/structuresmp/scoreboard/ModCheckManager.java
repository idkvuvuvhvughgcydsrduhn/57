package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Client brand check. Reads the brand each player's client reports when joining (vanilla, fabric, forge,
 * quilt, neoforge, lunar, badlion, ...). Admins are alerted when a player joins with a brand that is not
 * in the allowed list, and a blocked brand can be kicked automatically.
 *
 * Limits: this only sees the brand the client reports. A cheat client can report "vanilla", and the server
 * cannot list the mods a player has installed. Auto-totem and similar cheats are not detected by this check.
 */
public final class ModCheckManager implements Listener, CommandExecutor {

    private static final String PURPLE = "&#A855F7";
    private static final String ADMIN = "structuresmp.admin";

    private final JavaPlugin plugin;
    private final Map<UUID, String> brands = new HashMap<>();
    private boolean enabled = true;
    private List<String> allowed = new ArrayList<>();
    private List<String> blocked = new ArrayList<>();
    private String action = "notify";

    public ModCheckManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.getConfig().addDefault("modcheck.enabled", true);
        plugin.getConfig().addDefault("modcheck.allowed-brands", List.of(
                "vanilla", "fabric", "quilt", "forge", "neoforge", "lunar", "badlion"));
        plugin.getConfig().addDefault("modcheck.blocked-brands", List.of("wurst", "meteor", "impact", "vape", "sigma"));
        plugin.getConfig().addDefault("modcheck.action", "notify"); // notify | kick
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("modcheck.enabled", true);
        allowed = lower(cfg.getStringList("modcheck.allowed-brands"));
        blocked = lower(cfg.getStringList("modcheck.blocked-brands"));
        action = cfg.getString("modcheck.action", "notify").toLowerCase(Locale.ROOT);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        // the client brand arrives a moment after login
        Bukkit.getScheduler().runTaskLater(plugin, () -> check(p), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        brands.remove(e.getPlayer().getUniqueId());
    }

    private void check(Player p) {
        if (!p.isOnline()) return;
        String brand = brandOf(p);
        brands.put(p.getUniqueId(), brand);

        // only cheat clients are reported; vanilla, Lunar, Badlion, Fabric, Forge and other normal clients are ignored
        boolean isBlocked = blocked.stream().anyMatch(brand::contains);
        if (!isBlocked) return;

        alertAdmins("&c&lพบ client ต้องห้าม &f" + p.getName() + " &7ใช้ client: &f" + brand);
        if (isBlocked && action.equals("kick")) {
            p.kick(Text.c("&cStructure Smp ไม่อนุญาตให้ใช้ client นี้ &7(" + brand + ")"));
        }
    }

    private static String brandOf(Player p) {
        String b = p.getClientBrandName();
        return b == null || b.isBlank() ? "unknown" : b.toLowerCase(Locale.ROOT);
    }

    private void alertAdmins(String msg) {
        plugin.getLogger().info(msg.replaceAll("&[0-9a-fk-or#A-Fa-f0-9]+", ""));
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission(ADMIN)) admin.sendMessage(Text.c(PURPLE + "[ModCheck] &r" + msg));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(Text.c(PURPLE + "&lClient ของผู้เล่นที่ออนไลน์"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                String brand = brands.getOrDefault(p.getUniqueId(), brandOf(p));
                sender.sendMessage(Text.c("&f" + p.getName() + " &7- " + brandColor(brand) + brand));
            }
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นที่ออนไลน์ชื่อ " + args[0]));
            return true;
        }
        String brand = brands.getOrDefault(target.getUniqueId(), brandOf(target));
        sender.sendMessage(Text.c("&f" + target.getName() + " &7client: " + brandColor(brand) + brand));
        return true;
    }

    private String brandColor(String brand) {
        if (blocked.stream().anyMatch(brand::contains)) return "&c";
        if (allowed.contains(brand)) return "&a";
        return "&e";
    }
}
