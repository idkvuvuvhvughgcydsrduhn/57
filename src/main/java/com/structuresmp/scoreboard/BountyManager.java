package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bounties. /bounty <player> <amount> puts money on a player's head; whoever kills that player
 * collects the whole bounty. A floating "ค่าหัว $X" text follows the player's head while a bounty is active.
 * Data: plugins/StructureScoreboard/bounties.yml
 */
public final class BountyManager implements Listener, TabExecutor {

    private static final String RED = "&#FF5555";
    private static final String GOLD = "&#FFD54A";
    private static final String PURPLE = "&#A855F7";

    private final JavaPlugin plugin;
    private final EconomyManager economy;
    private final File file;
    private final Map<UUID, Double> bounties = new LinkedHashMap<>();
    private final Map<UUID, TextDisplay> displays = new HashMap<>();
    private final Map<UUID, Double> shownAmount = new HashMap<>();
    private BukkitTask task;

    public BountyManager(JavaPlugin plugin, EconomyManager economy) {
        this.plugin = plugin;
        this.economy = economy;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        load();
    }

    public void reload() {
        plugin.getConfig().addDefault("bounty.min-amount", 1000);
        plugin.getConfig().addDefault("bounty.broadcast", true);
        plugin.getConfig().addDefault("bounty.show-text", true);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (TextDisplay td : displays.values()) td.remove();
        displays.clear();
        shownAmount.clear();
        save();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yml.getConfigurationSection("bounties");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                double amount = section.getDouble(key);
                if (amount > 0) bounties.put(UUID.fromString(key), amount);
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : bounties.entrySet()) {
            yml.set("bounties." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save bounties.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ floating text

    private static net.kyori.adventure.text.Component label(double amount) {
        return Text.c(RED + "&lค่าหัว\n" + GOLD + "$" + Text.money(amount));
    }

    private static boolean visible(Player p) {
        return p.getGameMode() != GameMode.SPECTATOR && !p.isInvisible();
    }

    /** Every tick: keep the text glued above the head of each player who has a bounty. */
    private void tick() {
        boolean show = plugin.getConfig().getBoolean("bounty.show-text", true);

        Iterator<Map.Entry<UUID, TextDisplay>> it = displays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TextDisplay> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (!show || p == null || !bounties.containsKey(e.getKey()) || !visible(p)) {
                e.getValue().remove();
                shownAmount.remove(e.getKey());
                it.remove();
            }
        }
        if (!show) return;

        for (Map.Entry<UUID, Double> b : bounties.entrySet()) {
            Player p = Bukkit.getPlayer(b.getKey());
            if (p == null || !visible(p)) continue;

            Location loc = p.getEyeLocation().add(0, 0.95, 0);
            TextDisplay td = displays.get(b.getKey());
            if (td == null || !td.isValid() || !td.getWorld().equals(loc.getWorld())) {
                if (td != null) td.remove();
                td = spawnDisplay(p, loc, b.getValue());
                displays.put(b.getKey(), td);
                shownAmount.put(b.getKey(), b.getValue());
            } else {
                td.teleport(loc);
                Double shown = shownAmount.get(b.getKey());
                if (shown == null || Math.abs(shown - b.getValue()) > 0.001) {
                    td.text(label(b.getValue()));
                    shownAmount.put(b.getKey(), b.getValue());
                }
            }
        }
    }

    private TextDisplay spawnDisplay(Player owner, Location loc, double amount) {
        TextDisplay td = loc.getWorld().spawn(loc, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setShadowed(true);
            e.setSeeThrough(false);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setViewRange(0.5f);
            e.setTeleportDuration(2);
            e.text(label(amount));
        });
        owner.hideEntity(plugin, td); // the player with the bounty does not see their own text
        return td;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Double amount = bounties.get(dead.getUniqueId());
        if (amount == null) return;
        Player killer = dead.getKiller();
        if (killer == null || killer.equals(dead)) return;

        bounties.remove(dead.getUniqueId());
        save();
        economy.add(killer.getUniqueId(), amount);
        economy.save();
        Bukkit.broadcast(Text.c(RED + "&l[ค่าหัว] &f" + killer.getName() + " &7ฆ่า &f" + dead.getName()
                + " &7และได้รับค่าหัว " + GOLD + "$" + Text.money(amount)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Double amount = bounties.get(p.getUniqueId());
        if (amount == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) {
                p.sendMessage(Text.c(RED + "&l[ค่าหัว] &7มีค่าหัวบนตัวคุณ " + GOLD + "$" + Text.money(amount)
                        + " &7ระวังตัวด้วย!"));
            }
        }, 40L);
    }

    // ------------------------------------------------------------------ command

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

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            list(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("set") || sub.equals("remove")) {
            admin(sender, sub, args);
            return true;
        }
        if (args.length == 1) {
            show(sender, args[0]);
            return true;
        }
        place(sender, args[0], args[1]);
        return true;
    }

    private void list(CommandSender sender) {
        sender.sendMessage(Text.c(RED + "&l== ค่าหัวสูงสุด =="));
        if (bounties.isEmpty()) {
            sender.sendMessage(Text.c("&7ยังไม่มีใครถูกตั้งค่าหัว"));
        } else {
            List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(bounties.entrySet());
            sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            int rank = 1;
            for (Map.Entry<UUID, Double> e : sorted) {
                if (rank > 10) break;
                sender.sendMessage(Text.c("&e" + rank + ". &f" + economy.nameOf(e.getKey())
                        + " &7- " + GOLD + "$" + Text.money(e.getValue())));
                rank++;
            }
        }
        sender.sendMessage(Text.c("&7ตั้งค่าหัว: &f/bounty <ผู้เล่น> <จำนวนเงิน>"));
    }

    private void show(CommandSender sender, String name) {
        UUID id = resolve(name);
        if (id == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + name));
            return;
        }
        Double amount = bounties.get(id);
        sender.sendMessage(Text.c(amount == null
                ? "&f" + economy.nameOf(id) + " &7ไม่มีค่าหัว"
                : "&7ค่าหัวของ &f" + economy.nameOf(id) + "&7: " + GOLD + "$" + Text.money(amount)));
    }

    private void place(CommandSender sender, String targetName, String amountText) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        UUID target = resolve(targetName);
        if (target == null) {
            p.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + targetName));
            return;
        }
        if (target.equals(p.getUniqueId())) {
            p.sendMessage(Text.c("&cตั้งค่าหัวตัวเองไม่ได้"));
            return;
        }
        double amount = Text.parseAmount(amountText);
        double min = Math.max(1, plugin.getConfig().getDouble("bounty.min-amount", 1000));
        if (amount < min) {
            p.sendMessage(Text.c("&cค่าหัวขั้นต่ำ $" + Text.money(min) + " (พิมพ์เช่น 5000, 2.5k, 1m)"));
            return;
        }
        if (!economy.take(p.getUniqueId(), amount)) {
            p.sendMessage(Text.c("&cเงินไม่พอ คุณมี &a$" + Text.money(economy.get(p.getUniqueId()))));
            return;
        }

        double total = bounties.merge(target, amount, Double::sum);
        save();
        economy.save();

        String targetDisplay = economy.nameOf(target);
        String msg = RED + "&l[ค่าหัว] &f" + p.getName() + " &7ตั้งค่าหัว &f" + targetDisplay + " "
                + GOLD + "$" + Text.money(amount) + " &7(รวม " + GOLD + "$" + Text.money(total) + "&7)";
        if (plugin.getConfig().getBoolean("bounty.broadcast", true)) {
            Bukkit.broadcast(Text.c(msg));
        } else {
            p.sendMessage(Text.c(msg));
        }
    }

    private void admin(CommandSender sender, String sub, String[] args) {
        if (!sender.hasPermission("structuresmp.admin")) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.c("&eใช้: &f/bounty " + sub + " <ผู้เล่น>" + (sub.equals("set") ? " <จำนวนเงิน>" : "")));
            return;
        }
        UUID target = resolve(args[1]);
        if (target == null) {
            sender.sendMessage(Text.c("&cไม่พบผู้เล่นชื่อ " + args[1]));
            return;
        }
        String name = economy.nameOf(target);
        if (sub.equals("remove")) {
            if (bounties.remove(target) == null) {
                sender.sendMessage(Text.c("&f" + name + " &7ไม่มีค่าหัว"));
                return;
            }
            save();
            sender.sendMessage(Text.c("&aลบค่าหัวของ &f" + name + " &aแล้ว"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(Text.c("&eใช้: &f/bounty set <ผู้เล่น> <จำนวนเงิน>"));
            return;
        }
        double amount = Text.parseAmount(args[2]);
        if (amount <= 0) {
            sender.sendMessage(Text.c("&cจำนวนเงินไม่ถูกต้อง"));
            return;
        }
        bounties.merge(target, amount, Double::sum);
        save();
        sender.sendMessage(Text.c("&aตั้งค่าหัว &f" + name + " &aเพิ่ม " + GOLD + "$" + Text.money(amount)
                + " &7(ฟรี ไม่หักเงิน)"));
        if (plugin.getConfig().getBoolean("bounty.broadcast", true)) {
            Bukkit.broadcast(Text.c(RED + "&l[ค่าหัว] &f" + name + " &7ถูกตั้งค่าหัว " + GOLD + "$"
                    + Text.money(bounties.get(target))));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("structuresmp.admin");
        if (args.length == 1) {
            out.add("list");
            if (admin) {
                out.add("set");
                out.add("remove");
            }
        } else if (!(args.length == 2 && admin
                && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove")))) {
            return List.of();
        }
        for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
