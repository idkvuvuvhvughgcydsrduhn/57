package com.structuresmp.scoreboard;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Crash-safe YAML storage for player data.
 *  - Writes go to a temp file first, then replace the real file in one step (a crash mid-save cannot
 *    leave a half-written file).
 *  - Before each replace, the old file is copied into plugins/StructureScoreboard/backups/ at most every
 *    10 minutes; the newest 72 copies (about 12 hours) are kept.
 *  - If the file cannot be read, it is copied to <name>.corrupt-<time> and the newest readable backup is
 *    loaded instead. If nothing can be loaded, the caller is told not to save, so the broken file is never
 *    overwritten with empty data.
 */
final class SafeYaml {

    private static final long BACKUP_EVERY_MS = 10 * 60 * 1000L;
    private static final int KEEP = 72;

    static final class Result {
        final YamlConfiguration yml;
        final boolean trusted; // false = do not save over this file

        Result(YamlConfiguration yml, boolean trusted) {
            this.yml = yml;
            this.trusted = trusted;
        }
    }

    private SafeYaml() {
    }

    static Result load(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) return new Result(new YamlConfiguration(), true); // first run

        YamlConfiguration ok = parse(file);
        if (ok != null) return new Result(ok, true);

        File bad = new File(plugin.getDataFolder(), name + ".corrupt-" + stamp());
        try {
            Files.copy(file.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // keep going; the warning below still explains what happened
        }
        plugin.getLogger().severe(name + " could not be read. A copy was saved as " + bad.getName()
                + " and the newest backup will be used.");

        for (File backup : listBackups(plugin, name)) {
            YamlConfiguration restored = parse(backup);
            if (restored != null) {
                plugin.getLogger().warning("Restored " + name + " from backup " + backup.getName());
                return new Result(restored, true);
            }
        }
        plugin.getLogger().severe(name + " has no readable backup. Saving is disabled for this file so the "
                + "broken data is not overwritten. Fix or restore the file manually.");
        return new Result(new YamlConfiguration(), false);
    }

    static boolean write(JavaPlugin plugin, String name, YamlConfiguration yml) {
        File dir = plugin.getDataFolder();
        File target = new File(dir, name);
        File tmp = new File(dir, name + ".tmp");
        try {
            dir.mkdirs();
            Files.writeString(tmp.toPath(), yml.saveToString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (target.exists()) backupIfDue(plugin, name, target);
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save " + name + ": " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private static YamlConfiguration parse(File file) {
        try {
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (text.isBlank()) return null;
            YamlConfiguration yml = new YamlConfiguration();
            yml.loadFromString(text);
            return yml;
        } catch (IOException | InvalidConfigurationException e) {
            return null;
        }
    }

    private static File backupDir(JavaPlugin plugin) {
        return new File(plugin.getDataFolder(), "backups");
    }

    private static void backupIfDue(JavaPlugin plugin, String name, File target) throws IOException {
        File dir = backupDir(plugin);
        dir.mkdirs();
        List<File> existing = listBackups(plugin, name);
        if (!existing.isEmpty()
                && System.currentTimeMillis() - existing.get(0).lastModified() < BACKUP_EVERY_MS) {
            return;
        }
        File copy = new File(dir, name + "-" + stamp() + ".bak");
        Files.copy(target.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING);
        List<File> all = listBackups(plugin, name);
        for (int i = KEEP; i < all.size(); i++) all.get(i).delete();
    }

    /** Backups of one file, newest first. */
    static List<File> listBackups(JavaPlugin plugin, String name) {
        File[] files = backupDir(plugin).listFiles((d, n) -> n.startsWith(name + "-") && n.endsWith(".bak"));
        List<File> out = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private static String stamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }
}
