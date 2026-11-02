package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RandomTeleport {

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.WATER, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.FIRE, Material.SOUL_FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE);

    private final JavaPlugin plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private int minRadius = 200;
    private int maxRadius = 5000;
    private int cooldownSeconds = 60;

    public RandomTeleport(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        minRadius = Math.max(0, plugin.getConfig().getInt("rtp.min-radius", 200));
        maxRadius = Math.max(minRadius + 1, plugin.getConfig().getInt("rtp.max-radius", 5000));
        cooldownSeconds = Math.max(0, plugin.getConfig().getInt("rtp.cooldown-seconds", 60));
    }

    public void start(Player p) {
        UUID id = p.getUniqueId();
        if (busy.contains(id)) return;

        World world = p.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            p.sendMessage(Text.c("&cใช้ /rtp ได้เฉพาะในโลกปกติ (Overworld)"));
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
        p.sendMessage(Text.c("&eกำลังหาจุดวาร์ป..."));
        attempt(p, world, 0);
    }

    private void attempt(Player p, World world, int tries) {
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

        Location center = world.getSpawnLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double angle = rnd.nextDouble() * Math.PI * 2;
        double dist = minRadius + rnd.nextDouble() * (maxRadius - minRadius);
        int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
        int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

        if (!world.getWorldBorder().isInside(new Location(world, x, 64, z))) {
            attempt(p, world, tries + 1);
            return;
        }

        world.getChunkAtAsync(x >> 4, z >> 4)
                .thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin,
                        () -> finish(p, world, x, z, tries)))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> busy.remove(id));
                    return null;
                });
    }

    private void finish(Player p, World world, int x, int z, int tries) {
        UUID id = p.getUniqueId();
        if (!p.isOnline()) {
            busy.remove(id);
            return;
        }

        int y = world.getHighestBlockYAt(x, z);
        Block ground = world.getBlockAt(x, y, z);
        if (y <= world.getMinHeight() || !isSafe(ground)) {
            attempt(p, world, tries + 1);
            return;
        }

        Location dest = new Location(world, x + 0.5, y + 1, z + 0.5,
                p.getLocation().getYaw(), p.getLocation().getPitch());
        p.teleportAsync(dest)
                .thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(id);
                    if (ok) {
                        cooldowns.put(id, System.currentTimeMillis() + cooldownSeconds * 1000L);
                        p.sendMessage(Text.c("&aวาร์ปไปที่ &f" + x + ", " + (y + 1) + ", " + z));
                    } else {
                        p.sendMessage(Text.c("&cวาร์ปไม่สำเร็จ ลองใหม่อีกครั้ง"));
                    }
                }))
                .exceptionally(ex -> {
                    Bukkit.getScheduler().runTask(plugin, () -> busy.remove(id));
                    return null;
                });
    }

    private boolean isSafe(Block ground) {
        Material type = ground.getType();
        if (type.isAir() || ground.isLiquid() || UNSAFE.contains(type)) return false;
        Block above1 = ground.getRelative(BlockFace.UP);
        Block above2 = above1.getRelative(BlockFace.UP);
        return above1.isPassable() && above2.isPassable() && !above1.isLiquid();
    }
}
