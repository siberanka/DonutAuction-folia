package com.siberanka.donutauctions;

import com.siberanka.donutauctions.auction.AuctionService;
import com.siberanka.donutauctions.command.AhCommand;
import com.siberanka.donutauctions.config.LanguageManager;
import com.siberanka.donutauctions.filter.FilterManager;
import com.siberanka.donutauctions.gui.AuctionMenuService;
import com.siberanka.donutauctions.hook.EconomyHook;
import com.siberanka.donutauctions.hook.UltimateShopHook;
import com.siberanka.donutauctions.listener.AuctionInventoryListener;
import com.siberanka.donutauctions.listener.SearchInputListener;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DonutAuctionsPlugin extends JavaPlugin {

    private LanguageManager languageManager;
    private EconomyHook economyHook;
    private UltimateShopHook ultimateShopHook;
    private AuctionService auctionService;
    private AuctionMenuService menuService;
    private FilterManager filterManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("lang/en.yml", false);
        saveResource("lang/tr.yml", false);
        saveResource("filters.yml", false);

        languageManager = new LanguageManager(this);
        languageManager.reload();

        economyHook = new EconomyHook(this);
        economyHook.initialize();

        ultimateShopHook = new UltimateShopHook(this);
        ultimateShopHook.reload();

        filterManager = new FilterManager(this);
        filterManager.reload();

        auctionService = new AuctionService(this);
        auctionService.load();
        auctionService.startAutoSave();
        menuService = new AuctionMenuService(this, languageManager, auctionService, economyHook, ultimateShopHook, filterManager);

        AhCommand ahCommand = new AhCommand(this, languageManager, auctionService, menuService, economyHook, ultimateShopHook);
        PluginCommand command = getCommand("ah");
        if (command != null) {
            command.setExecutor(ahCommand);
            command.setTabCompleter(ahCommand);
        }

        getServer().getPluginManager().registerEvents(new AuctionInventoryListener(menuService), this);
        getServer().getPluginManager().registerEvents(new SearchInputListener(this, menuService, languageManager), this);
    }

    @Override
    public void onDisable() {
        if (menuService != null) {
            menuService.shutdown();
        }
        if (auctionService != null) {
            auctionService.shutdown();
        }
    }

    public void reloadPluginState() {
        reloadConfig();
        languageManager.reload();
        economyHook.initialize();
        ultimateShopHook.reload();
        filterManager.reload();
        auctionService.reloadRuntimeSchedulers();
        menuService.shutdown();
    }

    public UltimateShopHook ultimateShopHook() {
        return ultimateShopHook;
    }
}
