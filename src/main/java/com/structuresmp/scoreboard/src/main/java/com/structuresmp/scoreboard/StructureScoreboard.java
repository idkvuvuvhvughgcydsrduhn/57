package com.structuresmp.scoreboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
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

    /** Purple theme, used for new installs and when config-version is below 2. */
    private static final List<String> PURPLE_LINES = java.util.Arrays.asList(
            "&5&m                    ",
            "&d» &7ยศ: &f%rank%",
            "&d» &7เงิน: &f$%money%",
            "&d» &7พ้อย: &f%points%",
            "&d» &7คิล: &f%kills% &8(K/D &f%kd%&8)",
            "&d» &7ตาย: &f%deaths%",
            "&d» &7เวลาเล่น: &f%playtime%",
            "&d» &7ปิง: %ping%",
            "&5&m                    ");

    private final Map<UUID, Board> boards = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();

    private EconomyManager economy;
    private PointsManager points;
    private PriceManager prices;
    private AuctionManager auctions;
    private OrderManager orders;
    private ShopGui shopGui;
    private SellGui sellGui;
    private AuctionGui auctionGui;
    private OrderGui orderGui;
    private RandomTeleport rtp;
    private CombatManager combat;
    private MovementEffects effects;
    private HammerManager hammer;
    private SpawnerManager spawnerMgr;
    private SpawnerGui spawnerGui;
    private SpecialShopGui specialShop;
    private CodeManager codes;
    private BountyManager bounty;
    private TabListManager tabList;
    private RankManager ranks;
    private HomeManager homes;
    private SpecialTools specialTools;

    private BukkitTask task;
    private BukkitTask titleTask;
    private String title;
    private List<String> lines;

    // animated title settings
    private boolean animate;
    private String titleText = "Structure Smp";
    private int darkRgb = 0x4B0A8F;
    private int lightRgb = 0xE3B5FF;
    private double cycleSeconds = 3.0;
    private boolean titleBold = true;
    private final long animStart = System.currentTimeMillis();

    @Override
    public void onEnable() {
        getConfig().addDefault("title", "&d&lStructure Smp");
        getConfig().addDefault("update-ticks", 20);
        getConfig().addDefault("lines", PURPLE_LINES);
        getConfig().addDefault("animated-title.enabled", true);
        getConfig().addDefault("animated-title.text", "Structure Smp");
        getConfig().addDefault("animated-title.dark-color", "#4B0A8F");
        getConfig().addDefault("animated-title.light-color", "#E3B5FF");
        getConfig().addDefault("animated-title.cycle-seconds", 3.0);
        getConfig().addDefault("animated-title.bold", true);
        getConfig().addDefault("animated-title.update-ticks", 2);
        getConfig().addDefault("starting-balance", 1000);
        getConfig().addDefault("rtp.min-radius", 200);
        getConfig().addDefault("rtp.max-radius", 5000);
        getConfig().addDefault("rtp.cooldown-seconds", 60);
        getConfig().addDefault("ah.duration-hours", 48);
        getConfig().addDefault("ah.max-listings", 10);
        getConfig().addDefault("ah.max-price", 1000000000);
        getConfig().addDefault("ah.tax-percent", 5);
        getConfig().addDefault("orders.max-orders", 5);
        getConfig().addDefault("orders.max-amount", 2304);
        getConfig().addDefault("combat.enabled", true);
        getConfig().addDefault("combat.seconds", 20);
        getConfig().addDefault("combat.kill-on-quit", true);
        getConfig().addDefault("combat.blocked-commands", java.util.Arrays.asList(
                "rtp", "wild", "spawn", "home", "homes", "tpa", "tpahere", "tpaccept", "warp", "back", "tp"));
        getConfig().addDefault("effects.enabled", true);
        getConfig().addDefault("effects.walk", true);
        getConfig().addDefault("effects.sprint", true);
        getConfig().options().copyDefaults(true);

        // one-time switch of the scoreboard lines to the purple theme (with points)
        if (getConfig().getInt("config-version", 1) < 2) {
            getConfig().set("lines", PURPLE_LINES);
            getConfig().set("title", "&d&lStructure Smp");
            getConfig().set("config-version", 2);
        }
        saveConfig();

        economy = new EconomyManager(this);
        points = new PointsManager(this);
        prices = new PriceManager(this);
        auctions = new AuctionManager(this);
        orders = new OrderManager(this);
        rtp = new RandomTeleport(this);
        combat = new CombatManager(this);
        effects = new MovementEffects(this);
        hammer = new HammerManager(this);
        shopGui = new ShopGui(this, economy, prices);
        sellGui = new SellGui(this, economy, prices);
        auctionGui = new AuctionGui(this, economy, auctions);
        orderGui = new OrderGui(this, economy, orders);
        spawnerMgr = new SpawnerManager(this, prices);
        spawnerGui = new SpawnerGui(this, economy, spawnerMgr);
        spawnerMgr.setGui(spawnerGui);
        specialShop = new SpecialShopGui(this, economy, hammer, spawnerMgr, shopGui);
        shopGui.setSpecialShop(specialShop);
        codes = new CodeManager(this, economy, points);
        bounty = new BountyManager(this, economy);
        tabList = new TabListManager(this);
        ranks = new RankManager(this, economy);
        ranks.reload();
        homes = new HomeManager(this, combat, ranks);
        specialTools = new SpecialTools(this);

        Commands commands = new Commands(economy, points, prices, shopGui, sellGui, auctionGui, orderGui, rtp);
        for (String name : new String[] {"bal", "pay", "points", "addmoney", "removemoney", "addpoint", "removepoint",
                "baltop", "worth", "sell", "shop", "rtp", "ah", "orders"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) pc.setExecutor(commands);
        }
        getCommand("sb").setExecutor(this);
        getCommand("hammer").setExecutor(hammer);
        PluginCommand codeCommand = getCommand("code");
        if (codeCommand != null) codeCommand.setExecutor(codes);
        PluginCommand bountyCommand = getCommand("bounty");
        if (bountyCommand != null) bountyCommand.setExecutor(bounty);
        UtilityCommands utility = new UtilityCommands(combat);
        for (String name : new String[] {"ec", "nv"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) pc.setExecutor(utility);
        }
        for (String name : new String[] {"rank", "setrank"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) {
                pc.setExecutor(ranks);
                pc.setTabCompleter(ranks);
            }
        }
        for (String name : new String[] {"sethome", "home", "delhome", "homes"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) pc.setExecutor(homes);
        }
        for (String name : new String[] {"veinpick", "timberaxe"}) {
            PluginCommand pc = getCommand(name);
            if (pc != null) pc.setExecutor(specialTools);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(shopGui, this);
        getServer().getPluginManager().registerEvents(sellGui, this);
        getServer().getPluginManager().registerEvents(auctionGui, this);
        getServer().getPluginManager().registerEvents(orderGui, this);
        getServer().getPluginManager().registerEvents(combat, this);
        getServer().getPluginManager().registerEvents(hammer, this);
        getServer().getPluginManager().registerEvents(spawnerMgr, this);
        getServer().getPluginManager().registerEvents(spawnerGui, this);
        getServer().getPluginManager().registerEvents(specialShop, this);
        getServer().getPluginManager().registerEvents(bounty, this);
        getServer().getPluginManager().registerEvents(ranks, this);
        getServer().getPluginManager().registerEvents(specialTools, this);

        loadSettings();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            economy.save();
            points.save();
        }, 1200L, 1200L);
    }

    @Override
    public void onDisable() {
        if (sellGui != null) sellGui.closeAll(); // gives items back to players
        if (combat != null) combat.stop();
        if (effects != null) effects.stop();
        if (spawnerMgr != null) spawnerMgr.stop();
        if (bounty != null) bounty.stop();
        if (tabList != null) tabList.stop();
        if (ranks != null) ranks.stop();
        if (task != null) task.cancel();
        if (titleTask != null) titleTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) remove(p);
        boards.clear();
        if (economy != null) economy.save();
        if (points != null) points.save();
    }

    private void loadSettings() {
        reloadConfig();
        economy.setStartingBalance(getConfig().getDouble("starting-balance", 1000));
        prices.load();
        rtp.reload();
        combat.reload();
        effects.reload();
        hammer.reload();
        spawnerMgr.reload();
        specialShop.reload();
        bounty.reload();
        tabList.reload();
        if (ranks != null) ranks.reload();
        homes.reload();
        specialTools.reload();

        title = getConfig().getString("title", "&d&lStructure Smp");
        lines = getConfig().getStringList("lines");
        if (lines.size() > 15) lines = lines.subList(0, 15);
        long interval = Math.max(1L, getConfig().getLong("update-ticks", 20L));

        animate = getConfig().getBoolean("animated-title.enabled", true);
        titleText = getConfig().getString("animated-title.text", "Structure Smp");
        darkRgb = parseColor(getConfig().getString("animated-title.dark-color", "#4B0A8F"), 0x4B0A8F);
        lightRgb = parseColor(getConfig().getString("animated-title.light-color", "#E3B5FF"), 0xE3B5FF);
        cycleSeconds = Math.max(0.2, getConfig().getDouble("animated-title.cycle-seconds", 3.0));
        titleBold = getConfig().getBoolean("animated-title.bold", true);
        long animTicks = Math.max(1L, getConfig().getLong("animated-title.update-ticks", 2L));

        if (task != null) task.cancel();
        if (titleTask != null) titleTask.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) {
            remove(p);
            if (!hidden.contains(p.getUniqueId())) show(p);
        }
        task = Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, interval);
        titleTask = animate
                ? Bukkit.getScheduler().runTaskTimer(this, this::animateTitles, 1L, animTicks)
                : null;
    }

    private static int parseColor(String text, int fallback) {
        if (text == null) return fallback;
        String hex = text.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            return Integer.parseInt(hex, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** One frame of the title: a dark-to-light purple wave that travels along the text and loops forever. */
    private Component gradientTitle(long now) {
        double shift = ((now - animStart) / 1000.0) / cycleSeconds;
        int[] chars = titleText.codePoints().toArray();
        Component out = Component.empty();
        for (int i = 0; i < chars.length; i++) {
            double pos = (double) i / Math.max(1, chars.length);
            double v = 0.5 + 0.5 * Math.sin(2 * Math.PI * (pos - shift));
            Component part = Component.text(new String(Character.toChars(chars[i])),
                    TextColor.color(lerp(darkRgb, lightRgb, v)));
            if (titleBold) part = part.decorate(TextDecoration.BOLD);
            out = out.append(part);
        }
        return out;
    }

    private void animateTitles() {
        if (boards.isEmpty()) return;
        Component frame = gradientTitle(System.currentTimeMillis());
        for (Board b : boards.values()) b.objective.displayName(frame);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Board b = boards.get(p.getUniqueId());
            if (b != null) update(p, b);
        }
    }

    private void show(Player p) {
        Component initial = animate ? gradientTitle(System.currentTimeMillis()) : Text.c(title);
        Board b = new Board(initial, lines.size());
        boards.put(p.getUniqueId(), b);
        p.setScoreboard(b.scoreboard);
        update(p, b);
        // Rank teams must be applied to the new scoreboard immediately
        if (ranks != null) ranks.refreshAll();
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
                b.teams[i].prefix(Text.c(text));
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

        String moneyText = Text.money(economy.get(p.getUniqueId()));
        String pointsText = PointsManager.format(points.get(p.getUniqueId()));

        String rankText = ranks != null ? ranks.coloredName(p) : "&7-";

        return line
                .replace("%player%", p.getName())
                .replace("%rank%", rankText)
                .replace("%money%", moneyText)
                .replace("%points%", pointsText)
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
        Player p = e.getPlayer();
        economy.setName(p.getUniqueId(), p.getName());
        economy.get(p.getUniqueId()); // creates the account with the starting balance
        if (!hidden.contains(p.getUniqueId())) show(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        // this class only handles /sb; any command without its own executor must never toggle the scoreboard
        if (!cmd.getName().equalsIgnoreCase("sb")) return false;
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("structuresmp.admin")) {
                sender.sendMessage(Component.text("คุณไม่มีสิทธิ์ใช้คำสั่งนี้", NamedTextColor.RED));
                return true;
            }
            loadSettings();
            sender.sendMessage(Component.text("รีโหลดแล้ว", NamedTextColor.GREEN));
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
        final Objective objective;
        final Team[] teams;
        final String[] last;

        Board(Component title, int size) {
            scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            objective = scoreboard.registerNewObjective("structure", Criteria.DUMMY, title);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            teams = new Team[size];
            last = new String[size];
            for (int i = 0; i < size; i++) {
                // unique invisible entry per line; the visible text lives in the team prefix (no flicker)
                String entry = "\u00a7" + Integer.toHexString(i) + "\u00a7r";
                Team t = scoreboard.registerNewTeam("l" + i);
                t.addEntry(entry);
                objective.getScore(entry).setScore(size - i);
                teams[i] = t;
            }
        }
    }
}
