package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

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
 * Homes: /sethome [name], /home [name], /delhome <name>, /homes.
 * Data: plugins/StructureScoreboard/homes.yml
 */
public final class HomeManager implements TabExecutor {

    private static final String PURPLE = "&#A855F7";
    private static final String LIGHT = "&#D8B4FE";

    private record Home(String world, double x, double y, double z, float yaw, float pitch) {
        Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final JavaPlugin plugin;
    private final CombatManager combat;
    private final RankManager rankManager;
    private final File file;
    private final Map<UUID, Map<String, Home>> homes = new HashMap<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public HomeManager(JavaPlugin plugin, CombatManager combat, RankManager rankManager) {
        this.plugin = plugin;
        this.combat = combat;
        this.rankManager = rankManager;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        load();
    }

    public void reload() {
        plugin.getConfig().addDefault("homes.teleport-delay-seconds", 3);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    private int maxHomes(Player p) {
        return rankManager.maxHomes(p);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = yml.getConfigurationSection("homes");
        if (all == null) return;
        for (String uuid : all.getKeys(false)) {
            ConfigurationSection mine = all.getConfigurationSection(uuid);
            if (mine == null) continue;
            try {
                UUID id = UUID.fromString(uuid);
                Map<String, Home> map = new LinkedHashMap<>();
                for (String name : mine.getKeys(false)) {
                    ConfigurationSection s = mine.getConfigurationSection(name);
                    if (s == null) continue;
                    map.put(name, new Home(s.getString("world", ""), s.getDouble("x"), s.getDouble("y"),
                            s.getDouble("z"), (float) s.getDouble("yaw"), (float) s.getDouble("pitch")));
                }
                if (!map.isEmpty()) homes.put(id, map);
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Map<String, Home>> e : homes.entrySet()) {
            for (Map.Entry<String, Home> h : e.getValue().entrySet()) {
                String base = "homes." + e.getKey() + "." + h.getKey();
                Home home = h.getValue();
                yml.set(base + ".world", home.world());
                yml.set(base + ".x", home.x());
                yml.set(base + ".y", home.y());
                yml.set(base + ".z", home.z());
                yml.set(base + ".yaw", home.yaw());
                yml.set(base + ".pitch", home.pitch());
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save homes.yml: " + ex.getMessage());
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
            case "sethome" -> setHome(p, args);
            case "home" -> goHome(p, args);
            case "delhome" -> delHome(p, args);
            case "homes" -> list(p);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void setHome(Player p, String[] args) {
        String name = (args.length > 0 ? args[0] : "home").toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_-]{1,16}")) {
            p.sendMessage(Text.c("&cชื่อบ้านใช้ได้เฉพาะ a-z 0-9 _ - ความยาว 1-16 ตัว"));
            return;
        }
        Map<String, Home> mine = homes.computeIfAbsent(p.getUniqueId(), k -> new LinkedHashMap<>());
        boolean replacing = mine.containsKey(name);
        if (!replacing && mine.size() >= maxHomes(p)) {
            p.sendMessage(Text.c("&cคุณมีบ้านครบ " + maxHomes(p) + " หลังแล้ว ลบบ้านเก่าด้วย &f/delhome <ชื่อ> &cหรือตั้งทับชื่อเดิม"));
            return;
        }
        Location l = p.getLocation();
        mine.put(name, new Home(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
        save();
        p.sendMessage(Text.c(PURPLE + (replacing ? "ย้ายบ้าน " : "ตั้งบ้าน ") + LIGHT + name + PURPLE
                + " แล้ว &7(" + mine.size() + "/" + (maxHomes(p) == Integer.MAX_VALUE ? "∞" : maxHomes(p)) + ")"));
    }

    private void goHome(Player p, String[] args) {
        Map<String, Home> mine = homes.get(p.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            p.sendMessage(Text.c("&cคุณยังไม่มีบ้าน ใช้ &f/sethome &cเพื่อตั้งบ้าน"));
            return;
        }
        String name;
        if (args.length > 0) {
            name = args[0].toLowerCase(Locale.ROOT);
        } else if (mine.containsKey("home")) {
            name = "home";
        } else if (mine.size() == 1) {
            name = mine.keySet().iterator().next();
        } else {
            list(p);
            return;
        }
        Home home = mine.get(name);
        if (home == null) {
            p.sendMessage(Text.c("&cไม่พบบ้านชื่อ " + name));
            list(p);
            return;
        }
        if (combat.isInCombat(p)) {
            p.sendMessage(Text.c(PURPLE + "ใช้ /home ไม่ได้ขณะ Combat"));
            return;
        }
        Location dest = home.toLocation();
        if (dest == null) {
            p.sendMessage(Text.c("&cโลกของบ้านหลังนี้ไม่มีแล้ว"));
            return;
        }

        UUID id = p.getUniqueId();
        BukkitTask old = pending.remove(id);
        if (old != null) old.cancel();

        int delay = Math.max(0, plugin.getConfig().getInt("homes.teleport-delay-seconds", 3));
        if (delay == 0) {
            teleport(p, dest, name);
            return;
        }
        Location from = p.getLocation();
        p.sendMessage(Text.c(PURPLE + "วาร์ปไปบ้าน " + LIGHT + name + PURPLE + " ใน " + delay + " วินาที &7อย่าขยับ"));
        pending.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            if (!p.isOnline()) return;
            if (combat.isInCombat(p)) {
                p.sendMessage(Text.c(PURPLE + "ยกเลิกวาร์ป เพราะคุณติด Combat"));
                return;
            }
            Location now = p.getLocation();
            if (!now.getWorld().equals(from.getWorld()) || now.distanceSquared(from) > 1.0) {
                p.sendMessage(Text.c("&cยกเลิกวาร์ป เพราะคุณขยับ"));
                return;
            }
            teleport(p, dest, name);
        }, delay * 20L));
    }

    private void teleport(Player p, Location dest, String name) {
        p.teleportAsync(dest).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ok) p.sendMessage(Text.c(PURPLE + "ถึงบ้าน " + LIGHT + name + PURPLE + " แล้ว"));
            else p.sendMessage(Text.c("&cวาร์ปไม่สำเร็จ ลองใหม่อีกครั้ง"));
        }));
    }

    private void delHome(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(Text.c("&eใช้: &f/delhome <ชื่อบ้าน>"));
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        Map<String, Home> mine = homes.get(p.getUniqueId());
        if (mine == null || mine.remove(name) == null) {
            p.sendMessage(Text.c("&cไม่พบบ้านชื่อ " + name));
            return;
        }
        if (mine.isEmpty()) homes.remove(p.getUniqueId());
        save();
        p.sendMessage(Text.c(PURPLE + "ลบบ้าน " + LIGHT + name + PURPLE + " แล้ว"));
    }

    private void list(Player p) {
        Map<String, Home> mine = homes.get(p.getUniqueId());
        if (mine == null || mine.isEmpty()) {
            p.sendMessage(Text.c("&cคุณยังไม่มีบ้าน ใช้ &f/sethome &cเพื่อตั้งบ้าน"));
            return;
        }
        int max = maxHomes(p);
        Component line = Text.c(PURPLE + "บ้านของคุณ (" + mine.size() + "/" + (max == Integer.MAX_VALUE ? "∞" : max)
                + "): ");
        boolean first = true;
        for (Map.Entry<String, Home> e : mine.entrySet()) {
            Home h = e.getValue();
            if (!first) line = line.append(Text.c("&7, "));
            first = false;
            line = line.append(Text.c(LIGHT + e.getKey())
                    .clickEvent(ClickEvent.runCommand("/home " + e.getKey()))
                    .hoverEvent(HoverEvent.showText(Text.c("&7คลิกเพื่อวาร์ป\n&8" + h.world() + " "
                            + Math.round(h.x()) + ", " + Math.round(h.y()) + ", " + Math.round(h.z())))));
        }
        p.sendMessage(line);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player p) || args.length != 1 || !(name.equals("home") || name.equals("delhome"))) {
            return List.of();
        }
        Map<String, Home> mine = homes.get(p.getUniqueId());
        if (mine == null) return List.of();
        List<String> out = new ArrayList<>(mine.keySet());
        String prefix = args[0].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.startsWith(prefix));
        return out;
    }
}
