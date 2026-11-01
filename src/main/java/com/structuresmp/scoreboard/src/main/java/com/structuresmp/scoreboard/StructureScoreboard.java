package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StructureScoreboard extends JavaPlugin implements Listener, CommandExecutor {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private final Map<UUID, Board> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();

    private boolean vaultPresent;
    private VaultHook vault;
    private BukkitTask task;
    private String title;
    private List<String> lines;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        vaultPresent = getServer().getPluginManager().getPlugin("Vault") != null;
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("sb").setExecutor(this);
        loadSettings();
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) remove(p);
        boards.clear();
    }

    private void loadSettings() {
        reloadConfig();
        title = getConfig().getString("title", "&b&lStructure Smp");
        lines = getConfig().getStringList("lines");
        if (lines.size() > 15) lines = lines.subList(0, 15);
        long interval = Math.max(1L, getConfig().getLong("update-ticks", 20L));

        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            remove(p);
            if (!hidden.contains(p.getUniqueId())) show(p);
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, interval);
    }

    private void tick() {
        if (vaultPresent && vault == null) vault = VaultHook.create(this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board b = boards.get(p.getUniqueId());
            if (b != null) update(p, b);
        }
    }

    private void show(Player p) {
        Board b = new Board(LEGACY.deserialize(title), lines.size());
        boards.put(p.getUniqueId(), b);
        p.setScoreboard(b.scoreboard);
        update(p, b);
    }

    private void remove(Player p) {
        if (boards.remove(p.getUniqueId()) != null) {
            p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private void update(Player p, Board b) {
        for (int i = 0; i < lines.size(); i++) {
            String text = replace(lines.get(i), p);
            if (!text.equals(b.last[i])) {
                b.last[i] = text;
                b.teams[i].prefix(LEGACY.deserialize(text));
            }
        }
    }

    private String replace(String line, Player p) {
        int kills = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths = p.getStatistic(Statistic.DEATHS);
        double kd = deaths == 0 ? kills : (double) kills / deaths;

        long seconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
        String playtime = (seconds / 3600) + " ชม. " + ((seconds % 3600) / 60) + " น.";

        int ping = p.getPing();
        String pingText = (ping < 100 ? "&a" : ping < 200 ? "&e" : "&c") + ping + " ms";

        double money = vault == null ? 0 : vault.balance(p);
        String moneyText = money == Math.floor(money)
                ? String.format(Locale.US, "%,.0f", money)
                : String.format(Locale.US, "%,.2f", money);

        return line
                .replace("%player%", p.getName())
                .replace("%money%", moneyText)
                .replace("%kills%", String.valueOf(kills))
                .replace("%deaths%", String.valueOf(deaths))
                .replace("%kd%", String.format(Locale.US, "%.2f", kd))
                .replace("%playtime%", playtime)
                .replace("%ping%", pingText)
                .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
                .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!hidden.contains(e.getPlayer().getUniqueId())) show(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("structuresmp.admin")) {
                sender.sendMessage(Component.text("คุณไม่มีสิทธิ์ใช้คำสั่งนี้", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            sender.sendMessage(Component.text("รีโหลด Scoreboard แล้ว", NamedTextColor.GREEN));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (hidden.remove(p.getUniqueId())) {
            show(p);
            p.sendMessage(Component.text("เปิด Scoreboard แล้ว", NamedTextColor.GREEN));
        } else {
            hidden.add(p.getUniqueId());
            remove(p);
            p.sendMessage(Component.text("ปิด Scoreboard แล้ว", NamedTextColor.RED));
        }
        return true;
    }

    private static final class Board {
        final Scoreboard scoreboard;
        final Team[] teams;
        final String[] last;

        Board(Component title, int size) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = scoreboard.registerNewObjective("structure", Criteria.DUMMY, title);
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            teams = new Team[size];
            last = new String[size];
            for (int i = 0; i < size; i++) {
                // unique invisible entry per line; the visible text lives in the team prefix (no flicker)
                String entry = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
                Team t = scoreboard.registerNewTeam("l" + i);
                t.addEntry(entry);
                obj.getScore(entry).setScore(size - i);
                teams[i] = t;
            }
        }
    }
}
