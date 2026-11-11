package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Anti-ESP, only in the worlds listed in anti-esp.worlds (default: Survival).
 *
 * Players: hidden from each other when far away, or behind solid blocks past the grace distance.
 * Spawner cages and storage boxes (chests, trapped chests, ender chests, barrels, shulker boxes):
 * hidden (shown as air) from every viewer when far away or behind solid blocks past the grace distance.
 * Cages are not hidden from their owner. Storage boxes have no owner in vanilla, so nobody sees a
 * box beyond the grace distance unless there is a clear line of sight.
 * Hidden blocks are re-sent every few seconds because the server re-sends real chunk data on chunk reloads.
 *
 * Players with the permission structuresmp.antiesp.bypass (OP) see everything and are never hidden.
 * Ore/block x-ray is handled separately by Paper's built-in anti-xray (paper-global.yml).
 */
public final class AntiEspManager implements Listener {

    private static final String BYPASS = "structuresmp.antiesp.bypass";
    private static final int REFRESH_PASSES = 4;  // hidden blocks are re-sent every 4 box/cage passes
    private static final int BOX_EVERY = 3;       // storage boxes are checked every 3rd cycle (throttle)

    private final JavaPlugin plugin;
    private final Map<UUID, Set<UUID>> hidden = new HashMap<>();                       // viewer -> players hidden
    private final Map<UUID, Map<String, Location>> hiddenCages = new HashMap<>();      // viewer -> hidden cages
    private final Map<UUID, Map<String, Location>> hiddenBoxes = new HashMap<>();      // viewer -> hidden boxes
    private final Map<String, Set<Location>> boxesByChunk = new HashMap<>();           // registry of storage boxes
    private BukkitTask task;
    private SpawnerManager spawnerSource;
    private int cycle;
    private int passes;

    private boolean enabled = true;
    private boolean hideCages = true;
    private boolean hideBoxes = true;
    private List<String> worlds = new ArrayList<>();
    private double hideBeyond = 48;
    private double losGrace = 6;

    public AntiEspManager(JavaPlugin plugin) {
        this.plugin = plugin;
        scanAllLoadedChunks();
    }

    public void setSpawnerSource(SpawnerManager spawners) {
        this.spawnerSource = spawners;
    }

    public void reload() {
        plugin.getConfig().addDefault("anti-esp.enabled", true);
        plugin.getConfig().addDefault("anti-esp.worlds", List.of("Survival"));
        plugin.getConfig().addDefault("anti-esp.hide-beyond-blocks", 48);
        plugin.getConfig().addDefault("anti-esp.los-grace-blocks", 6);
        plugin.getConfig().addDefault("anti-esp.check-ticks", 10);
        plugin.getConfig().addDefault("anti-esp.spawners", true);
        plugin.getConfig().addDefault("anti-esp.boxes", true);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("anti-esp.enabled", true);
        hideCages = cfg.getBoolean("anti-esp.spawners", true);
        hideBoxes = cfg.getBoolean("anti-esp.boxes", true);
        worlds = cfg.getStringList("anti-esp.worlds");
        hideBeyond = Math.max(8, cfg.getDouble("anti-esp.hide-beyond-blocks", 48));
        losGrace = Math.max(0, cfg.getDouble("anti-esp.los-grace-blocks", 6));
        long interval = Math.max(1, cfg.getLong("anti-esp.check-ticks", 10));

        if (task != null) task.cancel();
        task = null;
        if (enabled) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
        } else {
            showAll();
        }
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        showAll();
    }

    private boolean watched(World w) {
        if (w == null) return false;
        for (String name : worlds) {
            if (name.equalsIgnoreCase(w.getName())) return true;
        }
        return false;
    }

    private void tick() {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        for (Player viewer : online) {
            if (!watched(viewer.getWorld()) || viewer.hasPermission(BYPASS)) {
                unhideAllFor(viewer);
                continue;
            }
            Set<UUID> mine = hidden.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
            for (Player target : online) {
                if (target.equals(viewer)) continue;
                boolean hide = target.getWorld().equals(viewer.getWorld())
                        && shouldHide(viewer, target) && !target.hasPermission(BYPASS);
                boolean isHidden = mine.contains(target.getUniqueId());
                if (hide && !isHidden) {
                    viewer.hideEntity(plugin, target);
                    mine.add(target.getUniqueId());
                } else if (!hide && isHidden) {
                    viewer.showEntity(plugin, target);
                    mine.remove(target.getUniqueId());
                }
            }
            if (mine.isEmpty()) hidden.remove(viewer.getUniqueId());
        }

        cycle++;
        boolean refresh = false;
        if (cycle % BOX_EVERY == 0) {
            passes++;
            refresh = passes % REFRESH_PASSES == 0;
        }
        for (Player viewer : online) {
            if (spawnerSource != null) hideCagesFor(viewer, refresh);
            if (cycle % BOX_EVERY == 0) hideBoxesFor(viewer, refresh);
        }
    }

    // ------------------------------------------------------------------ players

    private boolean shouldHide(Player viewer, Player target) {
        if (!target.getWorld().equals(viewer.getWorld())) return false;
        Location eye = viewer.getEyeLocation();
        Location feet = target.getLocation();
        double dist = eye.distance(feet);
        if (dist > hideBeyond) return true;
        if (dist <= losGrace) return false;

        Location[] points = {
                target.getEyeLocation(),
                feet.clone().add(0, 1.0, 0),
                feet.clone().add(0, 0.1, 0)
        };
        for (Location point : points) {
            if (!blocked(eye, point)) return false; // at least one point is visible
        }
        return true;
    }

    /** True when solid blocks stand between the two points. */
    private static boolean blocked(Location from, Location to) {
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 0.01) return false;
        RayTraceResult hit = from.getWorld().rayTraceBlocks(from, dir.normalize(), len,
                FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null;
    }

    private void unhideAllFor(Player viewer) {
        Set<UUID> set = hidden.remove(viewer.getUniqueId());
        if (set == null) return;
        for (UUID id : set) {
            Player t = Bukkit.getPlayer(id);
            if (t != null) viewer.showEntity(plugin, t);
        }
    }

    // ------------------------------------------------------------------ shared block hiding

    /** A block is hidden when it is out of range, or behind solid blocks past the grace distance. */
    private boolean blockHidden(Player viewer, Location block) {
        Location eye = viewer.getEyeLocation();
        double dist = eye.distance(block);
        if (dist > hideBeyond) return true;
        if (dist <= losGrace) return false;

        Vector dir = block.toVector().subtract(eye.toVector());
        double len = dir.length() - 0.6; // stop just before the block itself
        if (len < 0.1) return false;
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), len,
                FluidCollisionMode.NEVER, true);
        return hit != null && hit.getHitBlock() != null;
    }

    private static String keyOf(Location l) {
        return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ();
    }

    private void restore(Map<String, Location> mine, Player viewer) {
        for (Location loc : mine.values()) {
            if (loc.getWorld() == null || !loc.getWorld().equals(viewer.getWorld())) continue;
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            viewer.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    /** Applies hide/show for one set of blocks; returns the set of keys that are hidden now. */
    private void apply(Player viewer, Map<String, Location> mine, Location loc, boolean hide, boolean refresh) {
        String k = keyOf(loc);
        boolean isHidden = mine.containsKey(k);
        if (hide) {
            if (!isHidden || refresh) viewer.sendBlockChange(loc, Material.AIR.createBlockData());
            if (!isHidden) mine.put(k, loc);
        } else if (isHidden) {
            viewer.sendBlockChange(loc, loc.getBlock().getBlockData());
            mine.remove(k);
        }
    }

    // ------------------------------------------------------------------ spawner cages

    private void hideCagesFor(Player viewer, boolean refresh) {
        Map<String, Location> mine = hiddenCages.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
        boolean off = !hideCages || !enabled || !watched(viewer.getWorld()) || viewer.hasPermission(BYPASS);
        if (off) {
            restore(mine, viewer);
            hiddenCages.remove(viewer.getUniqueId());
            return;
        }

        Set<String> current = new HashSet<>();
        for (SpawnerManager.SpawnerData data : spawnerSource.all()) {
            current.add(data.key());
            World w = Bukkit.getWorld(data.world);
            if (w == null || !w.equals(viewer.getWorld())) continue;
            if (!w.isChunkLoaded(data.x >> 4, data.z >> 4)) continue;

            Location loc = new Location(w, data.x, data.y, data.z);
            if (data.owner.equals(viewer.getUniqueId())) {
                apply(viewer, mine, loc, false, false);
                continue;
            }
            Location centre = loc.clone().add(0.5, 0.5, 0.5);
            apply(viewer, mine, loc, blockHidden(viewer, centre), refresh);
        }
        mine.entrySet().removeIf(e -> !current.contains(e.getKey())
                || e.getValue().getWorld() == null
                || !e.getValue().getWorld().equals(viewer.getWorld()));
        if (mine.isEmpty()) hiddenCages.remove(viewer.getUniqueId());
    }

    // ------------------------------------------------------------------ storage boxes

    private static boolean isBox(Material m) {
        String n = m.name();
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.ENDER_CHEST
                || m == Material.BARREL || n.endsWith("SHULKER_BOX");
    }

    private static String chunkKey(String world, int cx, int cz) {
        return world + ":" + cx + ":" + cz;
    }

    private void scanAllLoadedChunks() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) scanChunk(c);
        }
    }

    private void scanChunk(Chunk c) {
        Set<Location> set = new HashSet<>();
        for (BlockState bs : c.getTileEntities()) {
            if (isBox(bs.getType())) set.add(bs.getBlock().getLocation());
        }
        if (!set.isEmpty()) boxesByChunk.put(chunkKey(c.getWorld().getName(), c.getX(), c.getZ()), set);
        else boxesByChunk.remove(chunkKey(c.getWorld().getName(), c.getX(), c.getZ()));
    }

    private void hideBoxesFor(Player viewer, boolean refresh) {
        Map<String, Location> mine = hiddenBoxes.computeIfAbsent(viewer.getUniqueId(), k -> new HashMap<>());
        boolean off = !hideBoxes || !enabled || !watched(viewer.getWorld()) || viewer.hasPermission(BYPASS);
        if (off) {
            restore(mine, viewer);
            hiddenBoxes.remove(viewer.getUniqueId());
            return;
        }

        World w = viewer.getWorld();
        Chunk centre = viewer.getLocation().getChunk();
        int radius = (int) Math.ceil(hideBeyond / 16.0) + 1;
        Set<String> seen = new HashSet<>();
        for (int cx = centre.getX() - radius; cx <= centre.getX() + radius; cx++) {
            for (int cz = centre.getZ() - radius; cz <= centre.getZ() + radius; cz++) {
                Set<Location> boxes = boxesByChunk.get(chunkKey(w.getName(), cx, cz));
                if (boxes == null) continue;
                for (Location loc : boxes) {
                    String k = keyOf(loc);
                    seen.add(k);
                    apply(viewer, mine, loc, blockHidden(viewer, loc.clone().add(0.5, 0.5, 0.5)), refresh);
                }
            }
        }
        // forget boxes that were broken or that are outside the scanned area
        mine.entrySet().removeIf(e -> !seen.contains(e.getKey()));
        if (mine.isEmpty()) hiddenBoxes.remove(viewer.getUniqueId());
    }

    // ------------------------------------------------------------------ cleanup

    private void showAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            unhideAllFor(viewer);
            Map<String, Location> cages = hiddenCages.get(viewer.getUniqueId());
            if (cages != null) restore(cages, viewer);
            Map<String, Location> boxes = hiddenBoxes.get(viewer.getUniqueId());
            if (boxes != null) restore(boxes, viewer);
        }
        hidden.clear();
        hiddenCages.clear();
        hiddenBoxes.clear();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        scanChunk(e.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Chunk c = e.getChunk();
        boxesByChunk.remove(chunkKey(c.getWorld().getName(), c.getX(), c.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!isBox(b.getType())) return;
        String k = chunkKey(b.getWorld().getName(), b.getChunk().getX(), b.getChunk().getZ());
        boxesByChunk.computeIfAbsent(k, x -> new HashSet<>()).add(b.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isBox(b.getType())) return;
        Set<Location> set = boxesByChunk.get(chunkKey(b.getWorld().getName(), b.getChunk().getX(), b.getChunk().getZ()));
        if (set != null) set.remove(b.getLocation());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        hidden.remove(id);
        hiddenCages.remove(id);
        hiddenBoxes.remove(id);
        for (Set<UUID> set : hidden.values()) set.remove(id);
    }
}
