package com.structuresmp.scoreboard;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /settings: per-player options.
 *  - Scoreboard: show or hide the sidebar
 *  - Chat: hide other players' chat messages
 *  - Pay: refuse incoming /pay
 *  - Mobs: hide every mob and animal within 67 blocks of you (only on your screen)
 * Data: plugins/StructureScoreboard/settings.yml
 */
public final class PlayerSettings implements Listener, CommandExecutor {

    /** Called when the scoreboard option changes, so the main class can show or remove the sidebar. */
    public interface ScoreboardToggle {
        void apply(Player p, boolean hidden);
    }

    private static final int RANGE = 67;
    private static final int MAX_HIDDEN = 250;      // cap per player, keeps packets bounded
    private static final long RESCAN_MS = 6000L;    // rescan at least this often
    private static final double RESCAN_DIST_SQ = 16.0; // or after moving 4 blocks
    private static final String PURPLE = "&#A855F7";
    private static final String HOLDER_NAME = "ตั้งค่าผู้เล่น";

    private final JavaPlugin plugin;
    private final Set<UUID> hiddenScoreboard;
    private final ScoreboardToggle scoreboardToggle;
    private final File file;
    private final Set<UUID> chatOff = new HashSet<>();
    private final Set<UUID> payOff = new HashSet<>();
    private final Set<UUID> mobsOff = new HashSet<>();
    private final Map<UUID, Set<UUID>> hiddenMobs = new HashMap<>();
    private final Map<UUID, org.bukkit.Location> lastScan = new HashMap<>();
    private final Map<UUID, Long> lastScanTime = new HashMap<>();
    private BukkitTask task;

    public PlayerSettings(JavaPlugin plugin, Set<UUID> hiddenScoreboard, ScoreboardToggle toggle) {
        this.plugin = plugin;
        this.hiddenScoreboard = hiddenScoreboard;
        this.scoreboardToggle = toggle;
        this.file = new File(plugin.getDataFolder(), "settings.yml");
        load();
        // checks every 2 seconds; the real scan only runs when the player moved or the timer is due
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMobs, 40L, 40L);
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        read(yml, "scoreboard", hiddenScoreboard);
        read(yml, "chat", chatOff);
        read(yml, "pay", payOff);
        read(yml, "mobs", mobsOff);
    }

    private static void read(YamlConfiguration yml, String key, Set<UUID> into) {
        for (String s : yml.getStringList(key)) {
            try {
                into.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip broken entries
            }
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        write(yml, "scoreboard", hiddenScoreboard);
        write(yml, "chat", chatOff);
        write(yml, "pay", payOff);
        write(yml, "mobs", mobsOff);
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save settings.yml: " + e.getMessage());
        }
    }

    private static void write(YamlConfiguration yml, String key, Set<UUID> set) {
        List<String> list = new ArrayList<>();
        for (UUID u : set) list.add(u.toString());
        yml.set(key, list);
    }

    // ------------------------------------------------------------------ menu

    public void open(Player p) {
        SettingsHolder holder = new SettingsHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c("&8ตั้งค่า"));
        holder.inventory = inv;
        ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);

        UUID id = p.getUniqueId();
        inv.setItem(10, toggleItem(Material.BOOK, "Scoreboard", "แสดงหรือซ่อนบอร์ดด้านขวา",
                !hiddenScoreboard.contains(id)));
        inv.setItem(12, toggleItem(Material.PAPER, "แชท", "แสดงหรือซ่อนข้อความแชทของผู้เล่นอื่น",
                !chatOff.contains(id)));
        inv.setItem(14, toggleItem(Material.GOLD_INGOT, "รับเงิน (/pay)", "อนุญาตหรือไม่ให้ผู้อื่นโอนเงินให้คุณ",
                !payOff.contains(id)));
        inv.setItem(16, toggleItem(Material.ZOMBIE_HEAD, "มอนสเตอร์ระยะ 67 บล็อก",
                "ซ่อนมอนสเตอร์และสัตว์รอบตัวบนหน้าจอคุณ", mobsOff.contains(id)));
        p.openInventory(inv);
    }

    private static ItemStack toggleItem(Material m, String name, String desc, boolean on) {
        return Gui.named(m, "&#C77DFF" + name,
                "&7" + desc,
                " ",
                on ? "&aเปิดอยู่ &7(คลิกเพื่อปิด)" : "&cปิดอยู่ &7(คลิกเพื่อเปิด)");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        open(p);
        return true;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SettingsHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int raw = e.getRawSlot();
        UUID id = p.getUniqueId();
        switch (raw) {
            case 10 -> {
                boolean nowHidden = !hiddenScoreboard.contains(id);
                if (nowHidden) hiddenScoreboard.add(id); else hiddenScoreboard.remove(id);
                scoreboardToggle.apply(p, nowHidden);
            }
            case 12 -> toggle(chatOff, id);
            case 14 -> toggle(payOff, id);
            case 16 -> {
                toggle(mobsOff, id);
                if (!mobsOff.contains(id)) restoreMobs(p);
            }
            default -> {
                return;
            }
        }
        save();
        Bukkit.getScheduler().runTask(plugin, () -> open(p));
    }

    private static void toggle(Set<UUID> set, UUID id) {
        if (!set.remove(id)) set.add(id);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SettingsHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent e) {
        Player sender = e.getPlayer();
        e.viewers().removeIf(a -> a instanceof Player vp && !vp.equals(sender) && chatOff.contains(vp.getUniqueId()));
    }

    // ------------------------------------------------------------------ pay

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPay(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        if (msg.length() < 2) return;
        String[] parts = msg.substring(1).split(" ");
        if (parts.length < 2) return;
        String label = parts[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!label.equals("pay")) return;

        String targetName = parts[1];
        UUID target = null;
        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            target = online.getUniqueId();
        } else {
            var off = Bukkit.getOfflinePlayerIfCached(targetName);
            if (off != null) target = off.getUniqueId();
        }
        if (target != null && payOff.contains(target)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Text.c(PURPLE + targetName + " &cปิดการรับเงินอยู่"));
        }
    }

    // ------------------------------------------------------------------ mobs within 67 blocks

    private void tickMobs() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!mobsOff.contains(id)) {
                restoreMobs(p);
                continue;
            }
            // skip the expensive scan while the player stays in place and the last scan is recent
            org.bukkit.Location here = p.getLocation();
            org.bukkit.Location last = lastScan.get(id);
            Long lastTime = lastScanTime.get(id);
            if (last != null && lastTime != null && last.getWorld() != null && last.getWorld().equals(here.getWorld())
                    && now - lastTime < RESCAN_MS && last.distanceSquared(here) < RESCAN_DIST_SQ) {
                continue;
            }
            lastScan.put(id, here.clone());
            lastScanTime.put(id, now);

            Set<UUID> mine = hiddenMobs.computeIfAbsent(id, k -> new HashSet<>());
            Set<UUID> seen = new HashSet<>();
            double maxSq = (double) RANGE * RANGE;
            for (Entity en : p.getWorld().getNearbyEntities(here, RANGE, RANGE, RANGE)) {
                if (!(en instanceof LivingEntity) || en instanceof Player) continue;
                if (en.getLocation().distanceSquared(here) > maxSq) continue;
                seen.add(en.getUniqueId());
                if (mine.size() < MAX_HIDDEN && mine.add(en.getUniqueId())) p.hideEntity(plugin, en);
            }
            Set<UUID> gone = new HashSet<>();
            for (UUID id : mine) {
                if (!seen.contains(id)) {
                    Entity en = Bukkit.getEntity(id);
                    if (en != null) p.showEntity(plugin, en);
                    gone.add(id);
                }
            }
            mine.removeAll(gone);
            if (mine.isEmpty()) hiddenMobs.remove(p.getUniqueId());
        }
    }

    private void restoreMobs(Player p) {
        Set<UUID> mine = hiddenMobs.remove(p.getUniqueId());
        if (mine == null) return;
        for (UUID id : mine) {
            Entity en = Bukkit.getEntity(id);
            if (en != null) p.showEntity(plugin, en);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hiddenMobs.remove(id);
        lastScan.remove(id);
        lastScanTime.remove(id);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Player p : Bukkit.getOnlinePlayers()) restoreMobs(p);
        hiddenMobs.clear();
    }

    private static final class SettingsHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
