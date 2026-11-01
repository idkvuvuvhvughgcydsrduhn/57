package com.structuresmp.scoreboard;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** Only loaded when Vault is installed, so the plugin still runs without it. */
final class VaultHook {

    private final Economy economy;

    private VaultHook(Economy economy) {
        this.economy = economy;
    }

    static VaultHook create(JavaPlugin plugin) {
        RegisteredServiceProvider<Economy> rsp =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : new VaultHook(rsp.getProvider());
    }

    double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }
}
