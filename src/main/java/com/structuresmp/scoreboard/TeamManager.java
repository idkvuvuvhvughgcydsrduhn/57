package com.structuresmp.scoreboard;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Teams / parties with a settings menu (/team).
 *  - Friendly fire: members can hurt each other only when this is ON (default OFF = teammates cannot be hit)
 *  - Open join: anyone can /team join without an invite
 *  - Team chat: when ON, your chat goes only to your team
 * Leader can change settings, kick, promote (leave), and disband. Max 10 members.
 * Data: plugins/StructureScoreboard/teams.yml
 */
public final class TeamManager implements Listener, TabExecutor {

    private static final int MAX_MEMBERS = 10;
    private static final long INVITE_MS = 60_000L;
    private static final String PURPLE = "&#A855F7";

    private static final class Team {
        final String name;
        UUID leader;
        final Set<UUID> members = new LinkedHashSet<>();
        boolean friendlyFire = false;
        boolean openJoin = false;

        Team(String name, UUID leader) {
            this.name = name;
            this.leader = leader;
            this.members.add(leader);
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Team> teams = new HashMap<>();          // lower-case name -> team
    private final Map<UUID, String> teamOf = new HashMap<>();         // player -> lower-case team name
    private final Map<UUID, Map<String, Long>> invites = new HashMap<>();
    private final Set<UUID> teamChat = new HashSet<>();

    public TeamManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "teams.yml");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("teams");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                Team t = new Team(s.getString(key + ".name", key), UUID.fromString(s.getString(key + ".leader", "")));
                t.members.clear();
                for (String m : s.getStringList(key + ".members")) t.members.add(UUID.fromString(m));
                if (t.members.isEmpty()) continue;
                t.friendlyFire = s.getBoolean(key + ".friendly-fire", false);
                t.openJoin = s.getBoolean(key + ".open-join", false);
                teams.put(key, t);
                for (UUID m : t.members) teamOf.put(m, key);
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Team> e : teams.entrySet()) {
            Team t = e.getValue();
            String base = "teams." + e.getKey();
            yml.set(base + ".name", t.name);
            yml.set(base + ".leader", t.leader.toString());
            List<String> ms = new ArrayList<>();
            for (UUID m : t.members) ms.add(m.toString());
            yml.set(base + ".members", ms);
            yml.set(base + ".friendly-fire", t.friendlyFire);
            yml.set(base + ".open-join", t.openJoin);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save teams.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private Team teamOfPlayer(UUID id) {
        String key = teamOf.get(id);
        return key == null ? null : teams.get(key);
    }

    private boolean sameTeam(UUID a, UUID b) {
        String ka = teamOf.get(a);
        return ka != null && ka.equals(teamOf.get(b));
    }

    private static String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        var off = Bukkit.getOfflinePlayer(id);
        return off.getName() == null ? "???" : off.getName();
    }

    private void tell(Team t, String msg) {
        for (UUID m : t.members) {
            Player p = Bukkit.getPlayer(m);
            if (p != null) p.sendMessage(Text.c(msg));
        }
    }

    // ------------------------------------------------------------------ friendly fire

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p) attacker = p;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) attacker = p;
        if (attacker == null || attacker.equals(victim)) return;

        Team t = teamOfPlayer(attacker.getUniqueId());
        if (t == null || !sameTeam(attacker.getUniqueId(), victim.getUniqueId())) return;
        if (t.friendlyFire) return;

        e.setCancelled(true);
        attacker.sendMessage(Text.c("&cตีเพื่อนร่วมทีมไม่ได้ (ทีม " + t.name + " ปิดการตีกันเอง)"));
    }

    // ------------------------------------------------------------------ team chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        if (!teamChat.contains(sender.getUniqueId())) return;
        if (teamOfPlayer(sender.getUniqueId()) == null) return;
        UUID sid = sender.getUniqueId();
        e.viewers().removeIf(a -> !(a instanceof Player vp) || !sameTeam(vp.getUniqueId(), sid));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        teamChat.remove(e.getPlayer().getUniqueId());
        invites.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ menu

    private void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&8ทีม"));
        holder.inventory = inv;
        ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);

        Team t = teamOfPlayer(p.getUniqueId());
        if (t == null) {
            inv.setItem(11, Gui.named(Material.EMERALD, "&aสร้างทีม",
                    "&7พิมพ์ &f/team create <ชื่อ>", "&7ชื่อ 3-16 ตัว (a-z 0-9 _)"));
            inv.setItem(15, Gui.named(Material.PAPER, "&eมีทีมอยู่แล้ว?",
                    "&7รอคำเชิญ หรือพิมพ์ &f/team accept <ทีม>"));
        } else {
            boolean leader = t.leader.equals(p.getUniqueId());
            inv.setItem(4, Gui.named(Material.WHITE_BANNER, "&#C77DFF" + t.name,
                    "&7หัวหน้า: &f" + nameOf(t.leader),
                    "&7สมาชิก: &f" + t.members.size() + "/" + MAX_MEMBERS));
            inv.setItem(10, toggle("ตีเพื่อนร่วมทีม", Material.IRON_SWORD, t.friendlyFire,
                    leader ? "หัวหน้าเท่านั้นที่เปลี่ยนได้" : "เฉพาะหัวหน้าเปลี่ยนได้"));
            inv.setItem(12, toggle("เปิดรับสมาชิกเอง", Material.OAK_DOOR, t.openJoin,
                    leader ? "คนนอกเข้าทีมได้โดยไม่ต้องรับคำเชิญ" : "เฉพาะหัวหน้าเปลี่ยนได้"));
            inv.setItem(14, toggle("แชทเฉพาะทีม", Material.PAPER, teamChat.contains(p.getUniqueId()),
                    "แชทของคุณจะส่งให้ทีมเท่านั้น"));
            List<String> names = new ArrayList<>();
            for (UUID m : t.members) names.add("&f" + nameOf(m) + (m.equals(t.leader) ? " &6(หัวหน้า)" : ""));
            inv.setItem(16, Gui.named(Material.PLAYER_HEAD, "&#C77DFFสมาชิก", names.toArray(new String[0])));
            inv.setItem(22, Gui.named(leader ? Material.TNT : Material.BARRIER,
                    leader ? "&cยุบทีม" : "&cออกจากทีม",
                    "&7คลิกเพื่อ" + (leader ? "ยุบทีม (สมาชิกทุกคนจะออก)" : "ออกจากทีม")));
            inv.setItem(20, Gui.named(Material.BOOK, "&eคำสั่ง",
                    "&f/team invite <ชื่อ> &7- เชิญ", "&f/team kick <ชื่อ> &7- ไล่ (หัวหน้า)",
                    "&f/team leave &7- ออกจากทีม", "&f/team chat &7- สลับแชททีม"));
        }
        p.openInventory(inv);
    }

    private static ItemStack toggle(String name, Material m, boolean on, String note) {
        return Gui.named(m, "&#C77DFF" + name,
                on ? "&aเปิดอยู่" : "&cปิดอยู่",
                "&7" + note,
                " ",
                "&eคลิกเพื่อสลับ");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Team t = teamOfPlayer(p.getUniqueId());
        int raw = e.getRawSlot();

        if (t == null) {
            p.closeInventory();
            p.sendMessage(Text.c(PURPLE + "พิมพ์ &f/team create <ชื่อ> &7เพื่อสร้างทีม"));
            return;
        }
        boolean leader = t.leader.equals(p.getUniqueId());
        switch (raw) {
            case 10 -> {
                if (!leader) { p.sendMessage(Text.c("&cเฉพาะหัวหน้าเปลี่ยนได้")); break; }
                t.friendlyFire = !t.friendlyFire;
                save();
                tell(t, PURPLE + "ตีเพื่อนร่วมทีม: " + (t.friendlyFire ? "&aเปิด" : "&cปิด"));
            }
            case 12 -> {
                if (!leader) { p.sendMessage(Text.c("&cเฉพาะหัวหน้าเปลี่ยนได้")); break; }
                t.openJoin = !t.openJoin;
                save();
                tell(t, PURPLE + "เปิดรับสมาชิกเอง: " + (t.openJoin ? "&aเปิด" : "&cปิด"));
            }
            case 14 -> {
                if (!teamChat.remove(p.getUniqueId())) teamChat.add(p.getUniqueId());
                p.sendMessage(Text.c(PURPLE + "แชทเฉพาะทีม: " + (teamChat.contains(p.getUniqueId()) ? "&aเปิด" : "&cปิด")));
            }
            case 22 -> {
                if (leader) disband(t, p);
                else leave(p);
                p.closeInventory();
                return;
            }
            default -> {
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> openMenu(p));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ actions

    private void create(Player p, String name) {
        if (teamOfPlayer(p.getUniqueId()) != null) {
            p.sendMessage(Text.c("&cคุณอยู่ในทีมแล้ว ออกก่อนจึงจะสร้างใหม่ได้"));
            return;
        }
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            p.sendMessage(Text.c("&cชื่อทีมใช้ได้เฉพาะ a-z 0-9 _ ยาว 3-16 ตัว"));
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (teams.containsKey(key)) {
            p.sendMessage(Text.c("&cมีทีมชื่อ " + name + " อยู่แล้ว"));
            return;
        }
        Team t = new Team(name, p.getUniqueId());
        teams.put(key, t);
        teamOf.put(p.getUniqueId(), key);
        save();
        p.sendMessage(Text.c(PURPLE + "สร้างทีม &f" + name + " &aแล้ว"));
    }

    private void invite(Player p, String targetName) {
        Team t = teamOfPlayer(p.getUniqueId());
        if (t == null) {
            p.sendMessage(Text.c("&cคุณยังไม่มีทีม"));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            p.sendMessage(Text.c("&cไม่พบผู้เล่นที่ออนไลน์ชื่อ " + targetName));
            return;
        }
        if (target.equals(p)) {
            p.sendMessage(Text.c("&cเชิญตัวเองไม่ได้"));
            return;
        }
        if (teamOfPlayer(target.getUniqueId()) != null) {
            p.sendMessage(Text.c("&c" + target.getName() + " มีทีมอยู่แล้ว"));
            return;
        }
        if (t.members.size() >= MAX_MEMBERS) {
            p.sendMessage(Text.c("&cทีมเต็มแล้ว (" + MAX_MEMBERS + " คน)"));
            return;
        }
        invites.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>())
                .put(teamOf.get(p.getUniqueId()), System.currentTimeMillis() + INVITE_MS);
        p.sendMessage(Text.c(PURPLE + "เชิญ &f" + target.getName() + " &7เข้าทีม " + t.name));
        Component accept = Text.c("&a&l[ยอมรับ]")
                .clickEvent(ClickEvent.runCommand("/team accept " + t.name))
                .hoverEvent(HoverEvent.showText(Text.c("&7คลิกเพื่อเข้าทีม")));
        target.sendMessage(Text.c(PURPLE + p.getName() + " &7เชิญคุณเข้าทีม &f" + t.name + " &7(60 วินาที) ").append(accept));
    }

    private void accept(Player p, String[] args) {
        if (teamOfPlayer(p.getUniqueId()) != null) {
            p.sendMessage(Text.c("&cคุณอยู่ในทีมแล้ว"));
            return;
        }
        Map<String, Long> mine = invites.get(p.getUniqueId());
        String key = null;
        if (args.length > 1) {
            key = args[1].toLowerCase(Locale.ROOT);
        } else if (mine != null && !mine.isEmpty()) {
            key = mine.keySet().iterator().next();
        }
        if (key == null || mine == null || !mine.containsKey(key) || mine.get(key) < System.currentTimeMillis()) {
            p.sendMessage(Text.c("&cไม่มีคำเชิญที่ใช้ได้"));
            return;
        }
        join(p, key);
    }

    private void join(Player p, String key) {
        Team t = teams.get(key);
        if (t == null) {
            p.sendMessage(Text.c("&cไม่พบทีมนี้แล้ว"));
            return;
        }
        if (t.members.size() >= MAX_MEMBERS) {
            p.sendMessage(Text.c("&cทีมเต็มแล้ว"));
            return;
        }
        t.members.add(p.getUniqueId());
        teamOf.put(p.getUniqueId(), key);
        Map<String, Long> mine = invites.get(p.getUniqueId());
        if (mine != null) mine.remove(key);
        save();
        tell(t, PURPLE + p.getName() + " &7เข้าทีม &f" + t.name);
    }

    private void leave(Player p) {
        Team t = teamOfPlayer(p.getUniqueId());
        if (t == null) {
            p.sendMessage(Text.c("&cคุณไม่ได้อยู่ในทีม"));
            return;
        }
        if (t.leader.equals(p.getUniqueId())) {
            if (t.members.size() == 1) {
                disband(t, p);
                return;
            }
            t.members.remove(p.getUniqueId());
            t.leader = t.members.iterator().next(); // promote the next member
            teamOf.remove(p.getUniqueId());
            save();
            tell(t, PURPLE + p.getName() + " ออกจากทีม &7หัวหน้าใหม่คือ &f" + nameOf(t.leader));
            p.sendMessage(Text.c(PURPLE + "ออกจากทีม &f" + t.name));
            return;
        }
        t.members.remove(p.getUniqueId());
        teamOf.remove(p.getUniqueId());
        teamChat.remove(p.getUniqueId());
        save();
        tell(t, PURPLE + p.getName() + " &7ออกจากทีม");
        p.sendMessage(Text.c(PURPLE + "ออกจากทีม &f" + t.name));
    }

    private void disband(Team t, Player by) {
        tell(t, "&cทีม " + t.name + " ถูกยุบแล้วโดย " + by.getName());
        for (UUID m : t.members) {
            teamOf.remove(m);
            teamChat.remove(m);
        }
        teams.remove(t.name.toLowerCase(Locale.ROOT));
        save();
    }

    private void kick(Player p, String targetName) {
        Team t = teamOfPlayer(p.getUniqueId());
        if (t == null || !t.leader.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cเฉพาะหัวหน้าทีมเท่านั้นที่ไล่คนได้"));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        UUID id = target != null ? target.getUniqueId() : null;
        if (id == null || !t.members.contains(id) || id.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cไม่พบสมาชิกชื่อ " + targetName + " ในทีม"));
            return;
        }
        t.members.remove(id);
        teamOf.remove(id);
        teamChat.remove(id);
        save();
        target.sendMessage(Text.c("&cคุณถูกไล่ออกจากทีม " + t.name));
        tell(t, PURPLE + target.getName() + " &7ถูกไล่ออกจากทีม");
    }

    // ------------------------------------------------------------------ /team

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            openMenu(p);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> {
                if (args.length < 2) p.sendMessage(Text.c("&eใช้: &f/team create <ชื่อ>"));
                else create(p, args[1]);
            }
            case "invite" -> {
                if (args.length < 2) p.sendMessage(Text.c("&eใช้: &f/team invite <ชื่อผู้เล่น>"));
                else invite(p, args[1]);
            }
            case "accept" -> accept(p, args);
            case "join" -> {
                if (args.length < 2) {
                    p.sendMessage(Text.c("&eใช้: &f/team join <ชื่อทีม>"));
                    break;
                }
                String key = args[1].toLowerCase(Locale.ROOT);
                Team t = teams.get(key);
                if (t == null || !t.openJoin) {
                    p.sendMessage(Text.c("&cทีมนี้ไม่เปิดรับสมาชิกเอง"));
                    break;
                }
                if (teamOfPlayer(p.getUniqueId()) != null) {
                    p.sendMessage(Text.c("&cคุณอยู่ในทีมแล้ว"));
                    break;
                }
                join(p, key);
            }
            case "leave" -> leave(p);
            case "kick" -> {
                if (args.length < 2) p.sendMessage(Text.c("&eใช้: &f/team kick <ชื่อผู้เล่น>"));
                else kick(p, args[1]);
            }
            case "disband" -> {
                Team t = teamOfPlayer(p.getUniqueId());
                if (t == null || !t.leader.equals(p.getUniqueId())) {
                    p.sendMessage(Text.c("&cเฉพาะหัวหน้าทีมเท่านั้นที่ยุบทีมได้"));
                } else {
                    disband(t, p);
                }
            }
            case "chat" -> {
                if (teamOfPlayer(p.getUniqueId()) == null) {
                    p.sendMessage(Text.c("&cคุณยังไม่มีทีม"));
                    break;
                }
                if (!teamChat.remove(p.getUniqueId())) teamChat.add(p.getUniqueId());
                p.sendMessage(Text.c(PURPLE + "แชทเฉพาะทีม: " + (teamChat.contains(p.getUniqueId()) ? "&aเปิด" : "&cปิด")));
            }
            case "info" -> {
                Team t = teamOfPlayer(p.getUniqueId());
                if (t == null) {
                    p.sendMessage(Text.c("&cคุณยังไม่มีทีม"));
                    break;
                }
                p.sendMessage(Text.c(PURPLE + "ทีม &f" + t.name + " &7| หัวหน้า &f" + nameOf(t.leader)
                        + " &7| สมาชิก &f" + t.members.size() + "/" + MAX_MEMBERS));
                p.sendMessage(Text.c("&7ตีเพื่อนร่วมทีม: " + (t.friendlyFire ? "&aเปิด" : "&cปิด")
                        + " &7| เปิดรับเอง: " + (t.openJoin ? "&aเปิด" : "&cปิด")));
            }
            case "gui", "menu" -> openMenu(p);
            default -> p.sendMessage(Text.c("&eใช้: &f/team &7| create | invite | accept | join | leave | kick | disband | chat | info"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player p)) return out;
        if (args.length == 1) {
            out.addAll(List.of("create", "invite", "accept", "join", "leave", "kick", "disband", "chat", "info", "gui"));
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("invite") || sub.equals("kick")) {
                for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
            } else if (sub.equals("join") || sub.equals("accept")) {
                for (Team t : teams.values()) if (t.openJoin || sub.equals("accept")) out.add(t.name);
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }

    /** Drop invites that have expired (called on a timer from the main class if needed). */
    public void cleanupInvites() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Map<String, Long>>> it = invites.entrySet().iterator(); it.hasNext(); ) {
            Map<String, Long> m = it.next().getValue();
            m.values().removeIf(v -> v < now);
            if (m.isEmpty()) it.remove();
        }
    }
}
