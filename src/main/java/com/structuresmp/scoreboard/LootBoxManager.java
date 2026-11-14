package com.structuresmp.scoreboard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Mystery boxes with optional keys.
 *  /lootbox create <id> [name]        create a box (name supports colour codes, e.g. &c&lGold &#FFD54Ahex)
 *  /lootbox rename <id> <name>        change the name (colour codes allowed)
 *  /lootbox desc <id> <text | text>   description lines, separated by |  (colour codes allowed)
 *  /lootbox keyed <id> on|off         require a matching key to open
 *  /lootbox edit <id>                 put rewards in the box (any items)
 *  /lootbox weight <id> <index> <w>   chance weight of a reward
 *  /lootbox give <player> <id> [n]    give boxes
 *  /lootbox givekey <player> <id> [n] give keys
 *  /lootbox list | info <id> | delete <id>
 * Right-click a box to open it (a keyed box uses one key from the inventory).
 * Data: plugins/StructureScoreboard/lootboxes.yml
 */
public final class LootBoxManager implements Listener, TabExecutor {

    private static final String ADMIN = "structuresmp.admin";
    private static final String PURPLE = "&#A855F7";
    private static final int SPIN_STEPS = 16;
    private static final Random RND = new Random();

    private static final class Box {
        final String id;
        String name;
        List<String> description = new ArrayList<>();
        boolean keyed;
        final List<ItemStack> rewards = new ArrayList<>();
        final List<Integer> weights = new ArrayList<>();

        Box(String id, String name) {
            this.id = id;
            this.name = name;
        }

        int totalWeight() {
            int t = 0;
            for (int w : weights) t += w;
            return t;
        }
    }

    private final JavaPlugin plugin;
    private final NamespacedKey boxKey;
    private final NamespacedKey keyKey;
    private final File file;
    private final Map<String, Box> boxes = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();

    public LootBoxManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.boxKey = new NamespacedKey(plugin, "lootbox_id");
        this.keyKey = new NamespacedKey(plugin, "lootbox_key");
        this.file = new File(plugin.getDataFolder(), "lootboxes.yml");
        load();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("boxes");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            Box b = new Box(id, s.getString(id + ".name", id));
            b.description = new ArrayList<>(yml.getStringList("boxes." + id + ".description"));
            b.keyed = yml.getBoolean("boxes." + id + ".keyed", false);
            List<?> items = yml.getList("boxes." + id + ".rewards", new ArrayList<>());
            List<Integer> ws = yml.getIntegerList("boxes." + id + ".weights");
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof ItemStack it)) continue;
                b.rewards.add(it);
                b.weights.add(i < ws.size() ? Math.max(1, ws.get(i)) : 1);
            }
            boxes.put(id, b);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Box b : boxes.values()) {
            yml.set("boxes." + b.id + ".name", b.name);
            yml.set("boxes." + b.id + ".description", b.description);
            yml.set("boxes." + b.id + ".keyed", b.keyed);
            yml.set("boxes." + b.id + ".rewards", b.rewards);
            yml.set("boxes." + b.id + ".weights", b.weights);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save lootboxes.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ items

    public ItemStack createBox(Box b) {
        ItemStack item = new ItemStack(Material.PURPLE_SHULKER_BOX);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore(b.name));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : b.description) lore.add(Text.lore(line));
        if (!b.description.isEmpty()) lore.add(Text.lore(" "));
        lore.add(Text.lore("&7กล่องสุ่ม &f" + b.rewards.size() + " &7รางวัล"));
        if (b.keyed) lore.add(Text.lore("&6ต้องใช้กุญแจเพื่อเปิด"));
        lore.add(Text.lore("&eคลิกขวาเพื่อเปิด"));
        lore.add(Text.lore(" "));
        lore.add(Text.lore(PURPLE + "Structure Smp"));
        meta.lore(lore);
        meta.getPersistentDataContainer().set(boxKey, PersistentDataType.STRING, b.id);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createKey(Box b) {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.lore("&6กุญแจ " + b.name));
        meta.lore(List.of(Text.lore("&7ใช้เปิด &f" + b.name), Text.lore("&8ใช้หนึ่งดอกต่อกล่องหนึ่งใบ")));
        meta.getPersistentDataContainer().set(keyKey, PersistentDataType.STRING, b.id);
        item.setItemMeta(meta);
        return item;
    }

    private Box boxOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(boxKey, PersistentDataType.STRING);
        return id == null ? null : boxes.get(id);
    }

    private String keyIdOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(keyKey, PersistentDataType.STRING);
    }

    /** Removes one matching key from the inventory. Returns false when the player has none. */
    private boolean takeKey(Player p, String boxId) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!boxId.equals(keyIdOf(it))) continue;
            if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1);
            else inv.setItem(i, null);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ opening

    @EventHandler
    public void onRightClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Box box = boxOf(hand);
        if (box == null) return;

        e.setCancelled(true); // do not place the shulker box
        if (box.rewards.isEmpty()) {
            p.sendMessage(Text.c("&cกล่องนี้ยังไม่มีรางวัล"));
            return;
        }
        if (opening.contains(p.getUniqueId())) return;
        if (box.keyed && !hasKey(p, box.id)) {
            p.sendMessage(Text.c("&cกล่องนี้ต้องใช้ &6กุญแจ " + box.name + " &cเพื่อเปิด"));
            return;
        }
        if (box.keyed) takeKey(p, box.id);
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1);
        else p.getInventory().setItemInMainHand(null);
        open(p, box);
    }

    private boolean hasKey(Player p, String boxId) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (boxId.equals(keyIdOf(it))) return true;
        }
        return false;
    }

    private void open(Player p, Box box) {
        UUID id = p.getUniqueId();
        opening.add(id);
        int winner = pick(box);
        ItemStack prize = box.rewards.get(winner).clone();
        final int[] step = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                task[0].cancel();
                opening.remove(id);
                return;
            }
            int t = step[0]++;
            if (t < SPIN_STEPS) {
                ItemStack shown = box.rewards.get(RND.nextInt(box.rewards.size()));
                p.sendActionBar(Text.c(PURPLE + "กำลังสุ่ม... &f" + Text.pretty(shown.getType())));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 0.8f + 0.02f * t);
            } else {
                task[0].cancel();
                opening.remove(id);
                giveReward(p, prize);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
                p.sendActionBar(Text.c(PURPLE + "ได้รับ &f" + Text.pretty(prize.getType()) + " &7x" + prize.getAmount()));
            }
        }, 0L, 3L);
    }

    private int pick(Box box) {
        int roll = RND.nextInt(Math.max(1, box.totalWeight()));
        int acc = 0;
        for (int i = 0; i < box.weights.size(); i++) {
            acc += box.weights.get(i);
            if (roll < acc) return i;
        }
        return box.weights.size() - 1;
    }

    private void giveReward(Player p, ItemStack prize) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(prize);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
    }

    // ------------------------------------------------------------------ editor

    private void openEditor(Player p, Box b) {
        EditorHolder holder = new EditorHolder(b.id);
        Inventory inv = Bukkit.createInventory(holder, 54, Text.c("&8แก้ไขรางวัล: " + b.id));
        holder.inventory = inv;
        for (int i = 0; i < Math.min(54, b.rewards.size()); i++) inv.setItem(i, b.rewards.get(i).clone());
        p.openInventory(inv);
    }

    @EventHandler
    public void onEditorClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof EditorHolder holder)) return;
        Box b = boxes.get(holder.boxId);
        if (b == null) return;
        List<ItemStack> newRewards = new ArrayList<>();
        List<Integer> newWeights = new ArrayList<>();
        for (int i = 0; i < 54; i++) {
            ItemStack it = e.getInventory().getItem(i);
            if (it == null || it.getType().isAir()) continue;
            newRewards.add(it.clone());
            newWeights.add(i < b.weights.size() ? b.weights.get(i) : 1);
        }
        b.rewards.clear();
        b.rewards.addAll(newRewards);
        b.weights.clear();
        b.weights.addAll(newWeights);
        save();
        if (e.getPlayer() instanceof Player p) {
            p.sendMessage(Text.c(PURPLE + "บันทึกกล่อง &f" + b.id + " &7(" + b.rewards.size() + " รางวัล)"));
        }
    }

    @EventHandler
    public void onEditorClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof EditorHolder)) return;
        if (e.getRawSlot() < 54 && (boxOf(e.getCursor()) != null || boxOf(e.getCurrentItem()) != null)) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) p.sendMessage(Text.c("&cใส่กล่องสุ่มเข้ากล่องสุ่มไม่ได้"));
        }
    }

    @EventHandler
    public void onEditorDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof EditorHolder)) return;
        if (boxOf(e.getOldCursor()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ /lootbox

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission(ADMIN)) {
            sender.sendMessage(Text.c("&cคุณไม่มีสิทธิ์ใช้คำสั่งนี้ (เฉพาะ OP)"));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "delete" -> delete(sender, args);
            case "list" -> list(sender);
            case "info" -> info(sender, args);
            case "edit" -> edit(sender, args);
            case "weight" -> weight(sender, args);
            case "rename" -> rename(sender, args);
            case "desc" -> desc(sender, args);
            case "keyed" -> keyed(sender, args);
            case "give" -> give(sender, args, false);
            case "givekey" -> give(sender, args, true);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(Text.c("&eใช้: &f/lootbox create <id> [ชื่อ] &7| &f/lootbox rename <id> <ชื่อ>"));
        s.sendMessage(Text.c("&f/lootbox desc <id> <ข้อความ | ข้อความ> &7| &f/lootbox keyed <id> on|off"));
        s.sendMessage(Text.c("&f/lootbox edit <id> &7| &f/lootbox weight <id> <ลำดับ> <น้ำหนัก>"));
        s.sendMessage(Text.c("&f/lootbox give <ผู้เล่น> <id> [n] &7| &f/lootbox givekey <ผู้เล่น> <id> [n]"));
        s.sendMessage(Text.c("&f/lootbox list &7| &f/lootbox info <id> &7| &f/lootbox delete <id>"));
        s.sendMessage(Text.c("&7สีใช้ &f&c &e &a &#RRGGBB &7และ &f&l ตัวหนา ได้"));
    }

    private static boolean validId(String id) {
        return id.matches("[a-z0-9_]{1,16}");
    }

    private static String rest(String[] args, int from) {
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }

    private Box find(CommandSender s, String[] args) {
        if (args.length < 2) return null;
        Box b = boxes.get(args[1].toLowerCase(Locale.ROOT));
        if (b == null) s.sendMessage(Text.c("&cไม่พบกล่องนี้"));
        return b;
    }

    private void create(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Text.c("&eใช้: &f/lootbox create <id> [ชื่อ]"));
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!validId(id)) {
            s.sendMessage(Text.c("&cรหัสกล่องใช้ได้เฉพาะ a-z 0-9 _ ยาวไม่เกิน 16 ตัว"));
            return;
        }
        if (boxes.containsKey(id)) {
            s.sendMessage(Text.c("&cมีกล่อง " + id + " อยู่แล้ว"));
            return;
        }
        String name = args.length > 2 ? rest(args, 2) : "&#C77DFF" + id;
        boxes.put(id, new Box(id, name));
        save();
        s.sendMessage(Text.c(PURPLE + "สร้างกล่อง " + name + " &7(" + id + ") แล้ว ใช้ &f/lootbox edit " + id
                + " &7เพื่อใส่รางวัล"));
    }

    private void rename(CommandSender s, String[] args) {
        Box b = find(s, args);
        if (b == null) return;
        if (args.length < 3) {
            s.sendMessage(Text.c("&eใช้: &f/lootbox rename <id> <ชื่อ>"));
            return;
        }
        b.name = rest(args, 2);
        save();
        s.sendMessage(Text.c("&aเปลี่ยนชื่อกล่องเป็น ").append(Text.c(b.name)));
    }

    private void desc(CommandSender s, String[] args) {
        Box b = find(s, args);
        if (b == null) return;
        if (args.length < 3) {
            s.sendMessage(Text.c("&eใช้: &f/lootbox desc <id> <ข้อความ | ข้อความ>  &7(คั่นบรรทัดด้วย |)"));
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String part : rest(args, 2).split("\\|")) {
            if (!part.isBlank()) lines.add(part.trim());
        }
        b.description = lines;
        save();
        s.sendMessage(Text.c("&aตั้งคำอธิบาย " + lines.size() + " บรรทัด"));
    }

    private void keyed(CommandSender s, String[] args) {
        Box b = find(s, args);
        if (b == null) return;
        if (args.length < 3 || !(args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("off"))) {
            s.sendMessage(Text.c("&eใช้: &f/lootbox keyed <id> on|off"));
            return;
        }
        b.keyed = args[2].equalsIgnoreCase("on");
        save();
        s.sendMessage(Text.c("&aกล่อง " + b.id + (b.keyed ? " ต้องใช้กุญแจแล้ว" : " ไม่ต้องใช้กุญแจแล้ว")));
    }

    private void delete(CommandSender s, String[] args) {
        if (args.length < 2 || boxes.remove(args[1].toLowerCase(Locale.ROOT)) == null) {
            s.sendMessage(Text.c("&cไม่พบกล่องนี้"));
            return;
        }
        save();
        s.sendMessage(Text.c("&aลบกล่องแล้ว (กล่องและกุญแจที่ผู้เล่นมีจะเปิดไม่ได้)"));
    }

    private void list(CommandSender s) {
        if (boxes.isEmpty()) {
            s.sendMessage(Text.c("&7ยังไม่มีกล่องสุ่ม"));
            return;
        }
        s.sendMessage(Text.c(PURPLE + "กล่องสุ่มทั้งหมด"));
        for (Box b : boxes.values()) {
            s.sendMessage(Text.c("&f" + b.id + " &7- " + b.name + " &8(" + b.rewards.size() + " รางวัล"
                    + (b.keyed ? ", ต้องใช้กุญแจ" : "") + ")"));
        }
    }

    private void info(CommandSender s, String[] args) {
        Box b = find(s, args);
        if (b == null) return;
        s.sendMessage(Text.c(PURPLE + "กล่อง " + b.id + " &7- น้ำหนักรวม " + b.totalWeight()
                + (b.keyed ? " &6(ต้องใช้กุญแจ)" : "")));
        s.sendMessage(Text.c("&7ชื่อ: " + b.name));
        for (int i = 0; i < b.rewards.size(); i++) {
            ItemStack it = b.rewards.get(i);
            int w = b.weights.get(i);
            double chance = b.totalWeight() == 0 ? 0 : 100.0 * w / b.totalWeight();
            s.sendMessage(Text.c("&7#" + i + " &f" + Text.pretty(it.getType()) + " x" + it.getAmount()
                    + " &7น้ำหนัก &f" + w + " &8(" + String.format(Locale.US, "%.1f", chance) + "%)"));
        }
    }

    private void edit(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) {
            s.sendMessage("Players only.");
            return;
        }
        Box b = find(s, args);
        if (b == null) return;
        openEditor(p, b);
    }

    private void weight(CommandSender s, String[] args) {
        Box b = find(s, args);
        if (b == null) return;
        if (args.length < 4) {
            s.sendMessage(Text.c("&eใช้: &f/lootbox weight <id> <ลำดับ> <น้ำหนัก>"));
            return;
        }
        int index, w;
        try {
            index = Integer.parseInt(args[2]);
            w = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            s.sendMessage(Text.c("&cลำดับและน้ำหนักต้องเป็นตัวเลข"));
            return;
        }
        if (index < 0 || index >= b.rewards.size() || w < 1 || w > 1_000_000) {
            s.sendMessage(Text.c("&cลำดับต้องอยู่ระหว่าง 0-" + (b.rewards.size() - 1) + " และน้ำหนัก 1-1000000"));
            return;
        }
        b.weights.set(index, w);
        save();
        s.sendMessage(Text.c("&aตั้งน้ำหนักรางวัล #" + index + " เป็น " + w));
    }

    private void give(CommandSender s, String[] args, boolean key) {
        if (args.length < 3) {
            s.sendMessage(Text.c("&eใช้: &f/lootbox " + (key ? "givekey" : "give") + " <ผู้เล่น> <id> [จำนวน]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        Box b = boxes.get(args[2].toLowerCase(Locale.ROOT));
        if (target == null || b == null) {
            s.sendMessage(Text.c("&cไม่พบผู้เล่นที่ออนไลน์หรือไม่พบกล่องนี้"));
            return;
        }
        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException e) {
                amount = 1;
            }
        }
        for (int i = 0; i < amount; i++) giveReward(target, key ? createKey(b) : createBox(b));
        String what = key ? "กุญแจ " : "";
        s.sendMessage(Text.c(PURPLE + "ให้ " + what + b.name + " &fx" + amount + " &7แก่ " + target.getName()));
        target.sendMessage(Text.c(PURPLE + "คุณได้รับ " + what + b.name + " &fx" + amount));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission(ADMIN)) return out;
        if (args.length == 1) {
            out.addAll(List.of("create", "delete", "list", "info", "edit", "weight", "rename", "desc", "keyed",
                    "give", "givekey"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givekey"))) {
            for (Player online : Bukkit.getOnlinePlayers()) out.add(online.getName());
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            out.addAll(boxes.keySet());
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("givekey"))) {
            out.addAll(boxes.keySet());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("keyed")) {
            out.addAll(List.of("on", "off"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }

    private static final class EditorHolder implements InventoryHolder {
        final String boxId;
        Inventory inventory;

        EditorHolder(String boxId) {
            this.boxId = boxId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
