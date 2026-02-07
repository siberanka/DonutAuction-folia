package com.siberanka.donutauctions.listener;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import com.siberanka.donutauctions.config.LanguageManager;
import com.siberanka.donutauctions.gui.AuctionMenuService;
import com.siberanka.donutauctions.util.SchedulerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class SearchInputListener implements Listener {

    private final DonutAuctionsPlugin plugin;
    private final AuctionMenuService menuService;
    private final LanguageManager lang;

    public SearchInputListener(DonutAuctionsPlugin plugin, AuctionMenuService menuService, LanguageManager lang) {
        this.plugin = plugin;
        this.menuService = menuService;
        this.lang = lang;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSearchInput(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!menuService.isWaitingSearch(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();
        if (message == null || message.isBlank()) {
            SchedulerAdapter.runSync(plugin, () -> player.sendMessage(lang.text("messages.search-empty")));
            return;
        }

        SchedulerAdapter.runSync(plugin, () -> menuService.applySearchInput(player, message.trim()));
    }
}
