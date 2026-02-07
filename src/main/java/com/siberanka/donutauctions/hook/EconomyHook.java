package com.siberanka.donutauctions.hook;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;

public final class EconomyHook {

    private final DonutAuctionsPlugin plugin;
    private Economy economy;

    public EconomyHook(DonutAuctionsPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("Vault")) {
            economy = null;
            return;
        }

        RegisteredServiceProvider<Economy> registration = plugin.getServer().getServicesManager().getRegistration(Economy.class);
        economy = registration != null ? registration.getProvider() : null;
    }

    public boolean isAvailable() {
        return economy != null;
    }

    public boolean hasEnough(Player player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public boolean isReady() {
        return isAvailable();
    }

    public boolean has(OfflinePlayer player, double amount) {
        return economy != null && economy.has(player, amount);
    }

    public boolean withdraw(OfflinePlayer player, double amount) {
        if (economy == null || amount < 0) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response.transactionSuccess();
    }

    public boolean deposit(OfflinePlayer player, double amount) {
        if (economy == null || amount < 0) {
            return false;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response.transactionSuccess();
    }

    public String format(double amount) {
        if (economy != null) {
            return economy.format(amount);
        }
        return String.format(Locale.US, "%.2f", amount);
    }
}
