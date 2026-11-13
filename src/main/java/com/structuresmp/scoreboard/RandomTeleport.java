package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /rtp opens a menu to pick a dimension: Overworld, Nether or The End.
 * Each dimension has its own portal animation (frames built one by one, with sound) shown only to the player.
 */
public final class RandomTeleport implements Listener {

    public enum Dim {
        OVERWORLD(World.Environment.NORMAL, "โลกปกติ", Material.GRASS_BLOCK,
                Material.END_PORTAL_FRAME, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f,
                Sound.BLOCK_END_PORTAL_SPAWN, Particle.PORTAL, Particle.PORTAL),
        NETHER(World.Environment.NETHER, "นรก (Nether)", Material.NETHERRACK,
                Material.OBSIDIAN, Sound.ITEM_FLINTANDSTEEL_USE, 1.0f,
                Sound.BLOCK_PORTAL_TRIGGER, Particle.FLAME, Particle.LAVA),
        END(World.Environment.THE_END, "The End", Material.END_STONE,
                Material.PURPUR_BLOCK, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.5f,
                Sound.BLOCK_END_GATEWAY_SPAWN, Particle.DRAGON_BREATH, Particle.END_ROD);

        final World.Environment env;
        final String label;
        final Material icon;
        final Material frame;
        final Sound fill;
        final float fillPitch;
        final Sound spawn;
        final Particle ringParticle;
        final Particle centreParticle;

        Dim(World.Environment env, String label, Material icon, Material frame, Sound fill, float fillPitch,
            Sound spawn, Particle ringParticle, Particle centreParticle) {
            this.env = env;
            this.label = label;
            this.icon = icon;
            this.frame = frame;
            this.fill = fill;
            this.fillPitch = fillPitch;
            this.spawn = spawn;
            this.ringParticle = ringParticle;
            this.centreParticle = centreParticle;
        }
    }

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE);
    private static final String PURPLE = "&#A855F7";
    private static final String MENU = "&8เลือกโลกที่จะวาร์ป";

    private final JavaPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> busy = new java.util.HashSet<>();
    private final Map<Dim, int[]> ranges = new EnumMap<>(Dim.class);
    private int minRadius = 200;
    private int cooldownSeconds = 60;
    private boolean animate = true;

    public RandomTeleport(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        var cfg = plugin.getConfig();
        cfg.addDefault("rtp.min-radius", 200);
        cfg.addDefault("rtp.max-radius", 5000);
        cfg.addDefault("rtp.nether-max-radius", 1000);
        cfg.addDefault("rtp.end-max-radius", 1000);
        cfg.addDefault("rtp.cooldown-seconds", 60);
        cfg.addDefault("rtp.animation", true);
        cfg.options().copyDefaults(true);
        plugin.saveConfig();

        minRadius = Math.max(0, cfg.getInt("rtp.min-radius", 200));
        cooldownSeconds = Math.max(0, cfg.getInt("rtp.cooldown-seconds", 60));
        animate = cfg.getBoolean("rtp.animation", true);
        ranges.put(Dim.OVERWORLD, new int[] {minRadius, Math.max(minRadius + 1, cfg.getInt("rtp.max-radius", 5000))});
        ranges.put(Dim.NETHER, new int[] {minRadius, Math.max(minRadius + 1, cfg.getInt("rtp.nether-max-radius", 1000))});
        ranges.put(Dim.END, new int[] {minRadius, Math.max(minRadius + 1, cfg.getInt("rtp.end-max-radius", 1000))});
    }

    // ------------------------------------------------------------------ menu

    /** Called by /rtp and /wild: opens the dimension menu. */
    public void start(Player p) {
        openMenu(p);
    }

    private void openMenu(Player p) {
        MenuHolder holder = new MenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Text.c(MENU));
        holder.inventory = inv;
        ItemStack pane = Gui.named(Material.PURPLE_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        inv.setItem(11, option(Dim.OVERWORLD));
        inv.setItem(13, option(Dim.NETHER));
        inv.setItem(15, option(Dim.END));
        p.openInventory(inv);
    }

    private ItemStack option(Dim d) {
        return Gui.named(d.icon, "&#C77DFF" + d.label,
                "&7สุ่มจุดที่ปลอดภัยในโลกนี้",
                "&7คูลดาวน์: &f" + cooldownSeconds + " วินาที",
                " ",
                "&eคลิกเพื่อวาร์ป");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Dim dim = switch (e.getRawSlot()) {
            case 11 -> Dim.OVERWORLD;
            case 13 -> Dim.NETHER;
            case 15 -> Dim.END;
            default -> null;
        };
        if (dim == null) return;
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> startDim(p, dim));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ teleport flow

    private void startDim(Player p, Dim dim) {
        UUID id = p.getUniqueId();
        if (busy.contains(id)) return;

        World world = findWorld(p, dim);
        if (world == null) {
            p.sendMessage(Text.c("&cโลก " + dim.label + " ยังไม่ได้ถูกสร้างบนเซิร์ฟเวอร์นี้"));
            return;
        }

        long now = System.currentTimeMillis();
        Long until = cooldowns.get(id);
        if (until != null && until > now && !p.hasPermission("structuresmp.rtp.bypass")) {
            long left = (until - now + 999) / 1000;
            p.sendMessage(Text.c("&cรออีก " + left + " วินาที ก่อนใช้ /rtp อีกครั้ง"));
            return;
        }

        busy.add(id);
        p.sendMessage(Text.c(PURPLE + "กำลังหาจุดวาร์ปไป " + dim.label + "..."));
        if (animate) {
            playAnimation(p, dim, () -> attempt(p, dim, world, 0));
        } else {
            attempt(p, dim, world, 0);
        }
    }

    private World findWorld(Player p, Dim dim) {
        if (p.getWorld().getEnvironment() == dim.env) return p.getWorld();
        for (World w : Bukkit.getWorlds()) {
            if (w.getEnvironment() == dim.env) return w;
        }
        return null;
    }

    private void attempt(Player p, Dim dim, World world, int tries) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) {
            busy.remove(id);
            return;
        }
        if (tries >= 12) {
            busy.remove(id);
            p.sendMessage(Text.c("&cหาจุดที่ปลอดภัยไม่เจอ ลองใหม่อีกครั้ง"));
            return;
        }

        Location center = dim == Dim.OVERWORLD ? world.getSpawnLocation() : new Location(world, 0, 0, 0);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int[] r = ranges.getOrDefault(dim, new int[] {minRadius, 1000});
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = r[0] + rnd.nextDouble() * (r[1] - r[0]);
        int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
        int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            attempt(p, dim, world, tries + 1);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4)
                .thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin,
                        () -> finish(p, dim, world, x, z, tries)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> busy.remove(id));
                    return null;
                });
    }

    private void finish(Player p, Dim dim, World world, int x, int z, int tries) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) {
            busy.remove(id);
            return;
        }
        int y = findY(dim, world, x, z);
        if (y < 0) {
            attempt(p, dim, world, tries + 1);
            return;
        }

        Location dest = new Location(world, x + 0.5, y, z + 0.5,
                p.getLocation().getYaw(), p.getLocation().getPitch());
        p.teleportAsync(dest)
                .thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (ok) {
                        cooldowns.put(id, System.currentTimeMillis() + cooldownSeconds * 1000L);
                        p.sendMessage(Text.c(PURPLE + "วาร์ปไป " + dim.label + " ที่ &f" + x + ", " + y + ", " + z));
                    } else {
                        p.sendMessage(Text.c("&cวาร์ปไม่สำเร็จ ลองใหม่อีกครั้ง"));
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> busy.remove(id));
                    return null;
                });
    }

    /** Standing Y at (x, z) for the dimension, or -1 when no safe spot is found. */
    private int findY(Dim dim, World world, int x, int z) {
        if (dim == Dim.OVERWORLD) {
            int y = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, y, z);
            return y <= world.getMinHeight() || !isSafe(ground) || !isSafeAbove(ground) ? -1 : y + 1;
        }
        int top = dim == Dim.NETHER ? 120 : 100;
        int bottom = dim == Dim.NETHER ? 32 : 40;
        for (int y = top; y >= bottom; y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (!ground.getType().isSolid() || !isSafe(ground)) continue;
            if (isSafeAbove(ground)) return y + 1;
        }
        return -1;
    }

    private static boolean isSafe(Block ground) {
        Material type = ground.getType();
        return !(type.isAir() || ground.isLiquid() || UNSAFE.contains(type));
    }

    private static boolean isSafeAbove(Block ground) {
        Block a1 = ground.getRelative(BlockFace.UP);
        Block a2 = a1.getRelative(BlockFace.UP);
        return a1.isPassable() && a2.isPassable() && !a1.isLiquid() && !a2.isLiquid();
    }

    // ------------------------------------------------------------------ animation

    /**
     * Builds a ring of the dimension's frame block around the player, one by one, with sound. Client-side only,
     * the real world is not changed. Then the centre flashes and the frames are removed before the teleport.
     */
    private void playAnimation(Player p, Dim dim, Runnable then) {
        final int period = 6;
        final int ringFrames = 8;
        final World w = p.getWorld();
        final int bx = p.getLocation().getBlockX();
        final int by = p.getLocation().getBlockY() - 1;
        final int bz = p.getLocation().getBlockZ();

        List<Location> ring = new ArrayList<>();
        int[][] offsets = {{-2, -2}, {0, -2}, {2, -2}, {2, 0}, {2, 2}, {0, 2}, {-2, 2}, {-2, 0}};
        for (int i = 0; i < ringFrames; i++) {
            ring.add(new Location(w, bx + offsets[i][0], by, bz + offsets[i][1]));
        }
        Location centre = new Location(w, bx + 0.5, by + 1.0, bz + 0.5);
        org.bukkit.block.data.BlockData frame = dim.frame.createBlockData();

        final int[] tick = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                task[0].cancel();
                restoreFrames(p, ring);
                busy.remove(p.getUniqueId());
                return;
            }
            int t = tick[0]++;
            if (t < ringFrames) {
                Location loc = ring.get(t);
                p.sendBlockChange(loc, frame);
                p.playSound(loc, dim.fill, 1.0f, dim.fillPitch + 0.05f * t);
                p.spawnParticle(dim.ringParticle, loc.clone().add(0.5, 1.0, 0.5), 12, 0.3, 0.2, 0.3, 0.1);
            } else if (t == ringFrames) {
                p.playSound(centre, dim.spawn, 1.0f, 1.0f);
                p.spawnParticle(dim.centreParticle, centre, 80, 1.5, 1.0, 1.5, 0.6);
            } else if (t >= ringFrames + 2) {
                task[0].cancel();
                restoreFrames(p, ring);
                then.run();
            }
        }, 0L, period);
    }

    private void restoreFrames(Player p, List<Location> ring) {
        for (Location loc : ring) {
            if (loc.getWorld() == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
            p.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
