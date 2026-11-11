package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
 *  /tpa <player>      ask to teleport TO that player
 *  /tpahere <player>  ask that player to teleport to YOU
 *  /tpaccept [player], /tpdeny [player], /tpacancel
 */
public final class TpaManager implements Listener, TabExecutor {

    private static final String PURPLE = "&#A855F7";
    private static final String LIGHT = "&#D8B4FE";

    private static final class Request {
        final UUID from;
        final UUID to;
        final boolean here; // true = /tpahere (the receiver teleports to the sender)
        final long expires;

        Request(UUID from, UUID to, boolean here, long expires) {
            this.from = from;
            this.to = to;
            this.here = here;
            this.expires = expires;
        }
    }

    private final JavaPlugin plugin;
    private final CombatManager combat;
    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new HashMap<>(); // receiver -> (sender -> request)
    private final Map<UUID, BukkitTask> pendingTeleports = new HashMap<>();
    private final Set<UUID> disabled = new HashSet<>(); // players who turned requests off (/tpatoggle)

    public TpaManager(JavaPlugin plugin, CombatManager combat) {
        this.plugin = plugin;
        this.combat = combat;
    }

    public void reload() {
        plugin.getConfig().addDefault("tpa.request-timeout-seconds", 60);
        plugin.getConfig().addDefault("tpa.teleport-delay-seconds", 3);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "tpa" -> sendRequest(p, args, false);
            case "tpahere" -> sendRequest(p, args, true);
            case "tpaccept" -> accept(p, args);
            case "tpdeny" -> deny(p, args);
            case "tpacancel" -> cancel(p);
            case "tpatoggle" -> toggle(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void sendRequest(Player from, String[] args, boolean here) {
        String cmdName = here ? "tpahere" : "tpa";
        if (args.length < 1) {
            from.sendMessage(Text.c("&eใช้: &f/" + cmdName + " <ผู้เล่น>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            from.sendMessage(Text.c("&cไม่พบผู้เล่นที่ออนไลน์ชื่อ " + args[0]));
            return;
        }
        if (target.equals(from)) {
            from.sendMessage(Text.c("&cส่งคำขอหาตัวเองไม่ได้"));
            return;
        }
        if (disabled.contains(target.getUniqueId())) {
            from.sendMessage(Text.c("&c" + target.getName() + " ปิดรับคำขอวาร์ปอยู่"));
            return;
        }
        if (combat.isInCombat(from)) {
            from.sendMessage(Text.c(PURPLE + "ใช้ /" + cmdName + " ไม่ได้ขณะ Combat"));
            return;
        }

        int timeout = Math.max(5, plugin.getConfig().getInt("tpa.request-timeout-seconds", 60));
        Request r = new Request(from.getUniqueId(), target.getUniqueId(), here,
                System.currentTimeMillis() + timeout * 1000L);
        incoming.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>()).put(from.getUniqueId(), r);

        from.sendMessage(Text.c(PURPLE + "ส่งคำขอ" + (here ? "ให้ " + LIGHT + target.getName() + PURPLE + " วาร์ปมาหาคุณ"
                : "วาร์ปไปหา " + LIGHT + target.getName()) + PURPLE + " แล้ว &7(หมดเวลาใน " + timeout + " วินาที)"));

        Component buttons = Text.c(PURPLE + LIGHT + from.getName() + PURPLE
                + (here ? " ขอให้คุณวาร์ปไปหาเขา " : " ขอวาร์ปมาหาคุณ "))
                .append(Text.c("&a&l[ยอมรับ]")
                        .clickEvent(ClickEvent.runCommand("/tpaccept " + from.getName()))
                        .hoverEvent(HoverEvent.showText(Text.c("&7คลิกเพื่อยอมรับ"))))
                .append(Text.c(" "))
                .append(Text.c("&c&l[ปฏิเสธ]")
                        .clickEvent(ClickEvent.runCommand("/tpdeny " + from.getName()))
                        .hoverEvent(HoverEvent.showText(Text.c("&7คลิกเพื่อปฏิเสธ"))));
        target.sendMessage(buttons);

        UUID fromId = from.getUniqueId();
        UUID toId = target.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Map<UUID, Request> map = incoming.get(toId);
            if (map != null && map.get(fromId) == r) {
                map.remove(fromId);
                Player sender = Bukkit.getPlayer(fromId);
                if (sender != null) sender.sendMessage(Text.c("&7คำขอวาร์ปของคุณหมดเวลาแล้ว"));
            }
        }, timeout * 20L);
    }

    /** The request this command refers to: the named sender, or the newest one. */
    private Request pick(Player target, String[] args) {
        Map<UUID, Request> map = incoming.get(target.getUniqueId());
        if (map == null || map.isEmpty()) return null;
        if (args.length > 0) {
            Player named = Bukkit.getPlayerExact(args[0]);
            return named == null ? null : map.get(named.getUniqueId());
        }
        Request last = null;
        for (Request r : map.values()) last = r;
        return last;
    }

    private void accept(Player target, String[] args) {
        Request r = pick(target, args);
        if (r == null) {
            target.sendMessage(Text.c("&cไม่มีคำขอวาร์ปที่รอคุณตอบอยู่"));
            return;
        }
        incoming.get(target.getUniqueId()).remove(r.from);
        if (System.currentTimeMillis() > r.expires) {
            target.sendMessage(Text.c("&cคำขอนี้หมดเวลาแล้ว"));
            return;
        }
        Player requester = Bukkit.getPlayer(r.from);
        if (requester == null) {
            target.sendMessage(Text.c("&cผู้ขอออกจากเกมไปแล้ว"));
            return;
        }

        Player mover = r.here ? target : requester;
        Player dest = r.here ? requester : target;
        if (combat.isInCombat(mover) || combat.isInCombat(dest)) {
            target.sendMessage(Text.c(PURPLE + "วาร์ปไม่ได้ เพราะมีคนติด Combat"));
            requester.sendMessage(Text.c(PURPLE + "วาร์ปไม่ได้ เพราะมีคนติด Combat"));
            return;
        }
        target.sendMessage(Text.c(PURPLE + "ยอมรับคำขอของ " + LIGHT + requester.getName() + PURPLE + " แล้ว"));
        requester.sendMessage(Text.c(LIGHT + target.getName() + PURPLE + " ยอมรับคำขอของคุณ"));
        startTeleport(mover, dest);
    }

    private void deny(Player target, String[] args) {
        Request r = pick(target, args);
        if (r == null) {
            target.sendMessage(Text.c("&cไม่มีคำขอวาร์ปที่รอคุณตอบอยู่"));
            return;
        }
        incoming.get(target.getUniqueId()).remove(r.from);
        target.sendMessage(Text.c(PURPLE + "ปฏิเสธคำขอแล้ว"));
        Player requester = Bukkit.getPlayer(r.from);
        if (requester != null) {
            requester.sendMessage(Text.c("&c" + target.getName() + " ปฏิเสธคำขอวาร์ปของคุณ"));
        }
    }

    private void cancel(Player p) {
        int n = 0;
        for (Map<UUID, Request> map : incoming.values()) {
            if (map.remove(p.getUniqueId()) != null) n++;
        }
        p.sendMessage(Text.c(n > 0 ? PURPLE + "ยกเลิกคำขอวาร์ป " + n + " รายการแล้ว" : "&7คุณไม่มีคำขอที่ส่งค้างอยู่"));
    }

    private void toggle(Player p) {
        if (disabled.remove(p.getUniqueId())) {
            p.sendMessage(Text.c(PURPLE + "เปิดรับคำขอวาร์ปแล้ว"));
        } else {
            disabled.add(p.getUniqueId());
            p.sendMessage(Text.c(PURPLE + "ปิดรับคำขอวาร์ปแล้ว (คนอื่นส่งคำขอหาคุณไม่ได้)"));
        }
    }

    // ------------------------------------------------------------------ teleporting

    private void startTeleport(Player mover, Player dest) {
        UUID id = mover.getUniqueId();
        BukkitTask old = pendingTeleports.remove(id);
        if (old != null) old.cancel();

        int delay = Math.max(0, plugin.getConfig().getInt("tpa.teleport-delay-seconds", 3));
        if (delay == 0) {
            go(mover, dest);
            return;
        }
        Location from = mover.getLocation();
        mover.sendMessage(Text.c(PURPLE + "วาร์ปไปหา " + LIGHT + dest.getName() + PURPLE + " ใน " + delay
                + " วินาที &7อย่าขยับ"));
        UUID destId = dest.getUniqueId();
        pendingTeleports.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingTeleports.remove(id);
            Player d = Bukkit.getPlayer(destId);
            if (!mover.isOnline() || d == null) {
                mover.sendMessage(Text.c("&cยกเลิกวาร์ป เพราะผู้เล่นออกจากเกม"));
                return;
            }
            if (combat.isInCombat(mover) || combat.isInCombat(d)) {
                mover.sendMessage(Text.c(PURPLE + "ยกเลิกวาร์ป เพราะมีคนติด Combat"));
                return;
            }
            Location now = mover.getLocation();
            if (!now.getWorld().equals(from.getWorld()) || now.distanceSquared(from) > 1.0) {
                mover.sendMessage(Text.c("&cยกเลิกวาร์ป เพราะคุณขยับ"));
                return;
            }
            go(mover, d);
        }, delay * 20L));
    }

    private void go(Player mover, Player dest) {
        mover.teleportAsync(dest.getLocation()).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ok) mover.sendMessage(Text.c(PURPLE + "วาร์ปสำเร็จ"));
            else mover.sendMessage(Text.c("&cวาร์ปไม่สำเร็จ ลองใหม่อีกครั้ง"));
        }));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        incoming.remove(id);
        for (Map<UUID, Request> map : incoming.values()) map.remove(id);
        BukkitTask t = pendingTeleports.remove(id);
        if (t != null) t.cancel();
    }

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
            Map<UUID, Request> map = incoming.get(p.getUniqueId());
            if (map != null) {
                for (UUID from : map.keySet()) {
                    Player s = Bukkit.getPlayer(from);
                    if (s != null) out.add(s.getName());
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
