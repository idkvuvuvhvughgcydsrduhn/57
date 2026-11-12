package com.structuresmp.scoreboard;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hides server-info commands from players, e.g. /plugins, /pl, /version, /ver, /about, /help.
 * Namespaced forms are blocked too: /bukkit:plugins, /minecraft:version, /paper:version, ...
 * Blocked commands are also removed from the TAB suggestions.
 * Players with structuresmp.admin (OP) can still use them and see them.
 * Configure in config.yml under "command-block".
 */
public final class CommandBlocker implements Listener {

    private final JavaPlugin plugin;
    private boolean enabled = true;
    private Set<String> commands = new HashSet<>();
    private Set<String> namespaces = new HashSet<>();
    private String message = "&cไม่รู้จักคำสั่งนี้";

    public CommandBlocker(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.getConfig().addDefault("command-block.enabled", true);
        plugin.getConfig().addDefault("command-block.commands", List.of(
                "plugins", "pl", "version", "ver", "about", "icanhasbukkit", "help", "?"));
        plugin.getConfig().addDefault("command-block.namespaces", List.of("bukkit", "spigot", "paper"));
        plugin.getConfig().addDefault("command-block.message", "&cไม่รู้จักคำสั่งนี้");
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("command-block.enabled", true);
        commands = new HashSet<>();
        for (String s : cfg.getStringList("command-block.commands")) commands.add(s.toLowerCase(Locale.ROOT));
        namespaces = new HashSet<>();
        for (String s : cfg.getStringList("command-block.namespaces")) namespaces.add(s.toLowerCase(Locale.ROOT));
        message = cfg.getString("command-block.message", "&cไม่รู้จักคำสั่งนี้");
    }

    /** True when the command label (without the slash, may include a namespace) must be hidden. */
    private boolean isBlocked(String rawLabel) {
        String label = rawLabel.toLowerCase(Locale.ROOT).trim();
        if (label.startsWith("/")) label = label.substring(1);
        if (label.isEmpty()) return false;

        String ns = null;
        String name = label;
        int colon = label.indexOf(':');
        if (colon >= 0) {
            ns = label.substring(0, colon);
            name = label.substring(colon + 1);
        }
        return commands.contains(label)
                || (commands.contains(name) && (ns == null || ns.equals("minecraft") || namespaces.contains(ns)))
                || (ns != null && namespaces.contains(ns));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.hasPermission("structuresmp.admin")) return;

        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String label = msg.substring(1).split(" ")[0];
        if (!isBlocked(label)) return;

        e.setCancelled(true);
        p.sendMessage(Text.c(message));
    }

    /** Removes blocked commands from the TAB list for non-OP players. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.hasPermission("structuresmp.admin")) return;
        e.getCommands().removeIf(this::isBlocked);
    }
}
