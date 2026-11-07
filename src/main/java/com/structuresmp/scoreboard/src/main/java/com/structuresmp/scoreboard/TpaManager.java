package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Teleport requests.
 *  /tpa <player>      - ask to teleport to a player
 *  /tpahere <player>  - ask a player to teleport to you
 *  /tpaccept [player], /tpdeny [player], /tpcancel, /tpatoggle
 */
public final class TpaManager implements Listener, TabExecutor {

    private static final String PURPLE = "&#A855F7";
    private static final String LIGHT = "&#D8B4FE";

    private record Request(UUID from, boolean here, long expires) {
    }

    private final JavaPlugin plugin;
    private final CombatManager combat;
    private final File file;
    private final Map<UUID, Map<UUID, Request>> incoming = new HashMap<>(); // target -> (requester -> request)
    private final Map<UUID, Long> lastRequest = new HashMap<>();
    private final Map<UUID, BukkitTask> moving = new HashMap<>();           // players waiting to be teleported
    private final Set<UUID> disabled = new HashSet<>();                      // players who turned requests off

    public TpaManager(JavaPlugin plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
        this.file = new File(plugin.getDataFolder(), "tpa.yml");
        load();
    }

    public void reload() {
        plugin.getConfig().addDefault("tpa.request-timeout-seconds", 60);
        plugin.getConfig().addDefault("tpa.teleport-delay-seconds", 3);
        plugin.getConfig().addDefault("tpa.cooldown-seconds", 5);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------ storage (who turned requests off)

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String id : yml.getStringList("disabled")) {
            try {
                disabled.add(UUID.fromString(id));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> ids = new ArrayList<>();
        for (UUID id : disabled) ids.add(id.toString());
        yml.set("disabled", ids);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save tpa.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> send(p, args, false);
            case "tpahere" -> send(p, args, true);
            case "tpaccept" -> accept(p, args);
            case "tpdeny" -> deny(p, args);
            case "tpcancel" -> cancel(p);
            case "tpatoggle" -> toggle(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void send(Player p, String[] args, boolean here) {
        if (args.length < 1) {
            p.sendMessage(Text.c("&eใช้: &f/" + (here ? "tpahere" : "tpa") + " <ผู้เล่น>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            p.sendMessage(Text.c("&cไม่พบผู้เล่นออนไลน์ชื่อ " + args[0]));
            return;
        }
        if (target.equals(p)) {
            p.sendMessage(Text.c("&cส่งคำขอหาตัวเองไม่ได้"));
            return;
        }
        if (combat.isInCombat(p)) {
            p.sendMessage(Text.c(PURPLE + "ใช้คำสั่งนี้ไม่ได้ขณะ Combat"));
            return;
        }
        if (disabled.contains(target.getUniqueId())) {
            p.sendMessage(Text.c("&c" + target.getName() + " ปิดรับคำขอวาร์ปอยู่"));
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, plugin.getConfig().getInt("tpa.cooldown-seconds", 5)) * 1000L;
        Long last = lastRequest.get(p.getUniqueId());
        if (last != null && now - last < cooldown) {
            p.sendMessage(Text.c("&cรอ " + ((cooldown - (now - last) + 999) / 1000) + " วินาทีก่อนส่งคำขออีกครั้ง"));
            return;
        }
        lastRequest.put(p.getUniqueId(), now);

        long timeout = Math.max(5, plugin.getConfig().getInt("tpa.request-timeout-seconds", 60));
        Map<UUID, Request> map = incoming.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>());
        map.remove(p.getUniqueId()); // a new request replaces the old one and becomes the most recent
        map.put(p.getUniqueId(), new Request(p.getUniqueId(), here, now + timeout * 1000L));

        p.sendMessage(Text.c(PURPLE + "ส่งคำขอ" + (here ? "ให้ " + LIGHT + target.getName() + PURPLE + " วาร์ปมาหาคุณ"
                : "วาร์ปไปหา " + LIGHT + target.getName()) + PURPLE + " แล้ว &7(หมดอายุใน " + timeout
                + " วินาที, ยกเลิกด้วย /tpcancel)"));

        Component accept = Text.c("&a&l[ยอมรับ]")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + p.getName()))
                .hoverEvent(HoverEvent.showText(Text.c("&7คลิกเพื่อยอมรับ")));
        Component deny = Text.c("&c&l[ปฏิเสธ]")
                .clickEvent(ClickEvent.runCommand("/tpdeny " + p.getName()))
                .hoverEvent(HoverEvent.showText(Text.c("&7คลิกเพื่อปฏิเสธ")));
        target.sendMessage(Text.c(LIGHT + p.getName() + PURPLE + (here ? " ขอให้คุณวาร์ปไปหาเขา" : " ขอวาร์ปมาหาคุณ"))
                .append(Component.newline()).append(accept).append(Text.c(" ")).append(deny));
    }

    /** Pending, not-yet-expired requests for a player. */
    private Map<UUID, Request> pending(UUID target) {
        Map<UUID, Request> map = incoming.get(target);
        if (map == null) return null;
        long now = System.currentTimeMillis();
        map.values().removeIf(r -> r.expires() < now);
        if (map.isEmpty()) {
            incoming.remove(target);
            return null;
        }
        return map;
    }

    /** The named request, or the most recent one when no name is given. Returns null (and tells the player) if none. */
    private Request pick(Player p, String[] args) {
        Map<UUID, Request> map = pending(p.getUniqueId());
        if (map == null) {
            p.sendMessage(Text.c("&cไม่มีคำขอวาร์ปที่รออยู่"));
            return null;
        }
        if (args.length > 0) {
            Player from = Bukkit.getPlayerExact(args[0]);
            Request r = from == null ? null : map.get(from.getUniqueId());
            if (r == null) p.sendMessage(Text.c("&cไม่มีคำขอจาก " + args[0]));
            return r;
        }
        Request last = null;
        for (Request r : map.values()) last = r;
        return last;
    }

    private void accept(Player p, String[] args) {
        Request r = pick(p, args);
        if (r == null) return;
        if (combat.isInCombat(p)) {
            p.sendMessage(Text.c(PURPLE + "ใช้คำสั่งนี้ไม่ได้ขณะ Combat"));
            return;
        }
        Player requester = Bukkit.getPlayer(r.from());
        pending(p.getUniqueId());
        Map<UUID, Request> map = incoming.get(p.getUniqueId());
        if (map != null) map.remove(r.from());
        if (requester == null) {
            p.sendMessage(Text.c("&cผู้ขอออกจากเกมไปแล้ว"));
            return;
        }

        Player mover = r.here() ? p : requester;     // the one who gets teleported
        Player target = r.here() ? requester : p;    // where they go
        p.sendMessage(Text.c(PURPLE + "ยอมรับคำขอจาก " + LIGHT + requester.getName()));
        requester.sendMessage(Text.c(LIGHT + p.getName() + PURPLE + " ยอมรับคำขอของคุณ"));
        begin(mover, target);
    }

    private void deny(Player p, String[] args) {
        Request r = pick(p, args);
        if (r == null) return;
        Map<UUID, Request> map = incoming.get(p.getUniqueId());
        if (map != null) map.remove(r.from());
        p.sendMessage(Text.c(PURPLE + "ปฏิเสธคำขอแล้ว"));
        Player requester = Bukkit.getPlayer(r.from());
        if (requester != null) requester.sendMessage(Text.c("&c" + p.getName() + " ปฏิเสธคำขอวาร์ปของคุณ"));
    }

    private void cancel(Player p) {
        int removed = 0;
        for (Map<UUID, Request> map : incoming.values()) {
            if (map.remove(p.getUniqueId()) != null) removed++;
        }
        p.sendMessage(Text.c(removed > 0 ? PURPLE + "ยกเลิกคำขอที่ส่งไปแล้ว" : "&cคุณไม่มีคำขอที่ส่งค้างอยู่"));
    }

    private void toggle(Player p) {
        UUID id = p.getUniqueId();
        if (disabled.remove(id)) {
            p.sendMessage(Text.c(PURPLE + "เปิดรับคำขอวาร์ปแล้ว"));
        } else {
            disabled.add(id);
            incoming.remove(id);
            p.sendMessage(Text.c(PURPLE + "ปิดรับคำขอวาร์ปแล้ว &7(พิมพ์ /tpatoggle อีกครั้งเพื่อเปิด)"));
        }
        save();
    }

    // ------------------------------------------------------------------ teleport

    /** The mover waits a few seconds without moving, then is teleported to the target player. */
    private void begin(Player mover, Player target) {
        if (combat.isInCombat(mover)) {
            mover.sendMessage(Text.c(PURPLE + "วาร์ปไม่ได้ขณะ Combat"));
            return;
        }
        UUID id = mover.getUniqueId();
        BukkitTask old = moving.remove(id);
        if (old != null) old.cancel();

        int delay = Math.max(0, plugin.getConfig().getInt("tpa.teleport-delay-seconds", 3));
        if (delay == 0) {
            teleport(mover, target);
            return;
        }
        Location from = mover.getLocation();
        mover.sendMessage(Text.c(PURPLE + "วาร์ปไปหา " + LIGHT + target.getName() + PURPLE + " ใน " + delay
                + " วินาที &7อย่าขยับ"));
        moving.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            moving.remove(id);
            if (!mover.isOnline() || !target.isOnline()) return;
            if (combat.isInCombat(mover)) {
                mover.sendMessage(Text.c(PURPLE + "ยกเลิกวาร์ป เพราะคุณติด Combat"));
                return;
            }
            Location now = mover.getLocation();
            if (!now.getWorld().equals(from.getWorld()) || now.distanceSquared(from) > 1.0) {
                mover.sendMessage(Text.c("&cยกเลิกวาร์ป เพราะคุณขยับ"));
                return;
            }
            teleport(mover, target);
        }, delay * 20L));
    }

    private void teleport(Player mover, Player target) {
        mover.teleportAsync(target.getLocation()).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ok) mover.sendMessage(Text.c(PURPLE + "วาร์ปไปหา " + LIGHT + target.getName() + PURPLE + " แล้ว"));
            else mover.sendMessage(Text.c("&cวาร์ปไม่สำเร็จ ลองใหม่อีกครั้ง"));
        }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        incoming.remove(id);
        for (Map<UUID, Request> map : incoming.values()) map.remove(id);
        lastRequest.remove(id);
        BukkitTask t = moving.remove(id);
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p) || args.length != 1) return List.of();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (name.equals("tpa") || name.equals("tpahere")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(p)) out.add(online.getName());
            }
        } else if (name.equals("tpaccept") || name.equals("tpdeny")) {
            Map<UUID, Request> map = pending(p.getUniqueId());
            if (map != null) {
                for (UUID from : map.keySet()) {
                    Player requester = Bukkit.getPlayer(from);
                    if (requester != null) out.add(requester.getName());
                }
            }
        } else {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
