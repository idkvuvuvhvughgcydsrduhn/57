package com.structuresmp.scoreboard;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/** /ec opens your ender chest, /nv toggles night vision. */
public final class UtilityCommands implements CommandExecutor {

    private static final String PURPLE = "&#A855F7";

    private final CombatManager combat;

    public UtilityCommands(CombatManager combat) {
        this.combat = combat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "ec" -> {
                if (combat.isInCombat(p)) {
                    p.sendMessage(Text.c(PURPLE + "เปิด Ender Chest ไม่ได้ขณะ Combat"));
                    return true;
                }
                p.openInventory(p.getEnderChest());
            }
            case "nv" -> {
                if (p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
                    p.removePotionEffect(PotionEffectType.NIGHT_VISION);
                    p.sendMessage(Text.c(PURPLE + "Night Vision: &cปิด"));
                } else {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION,
                            PotionEffect.INFINITE_DURATION, 0, false, false, false));
                    p.sendMessage(Text.c(PURPLE + "Night Vision: &aเปิด"));
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }
}
