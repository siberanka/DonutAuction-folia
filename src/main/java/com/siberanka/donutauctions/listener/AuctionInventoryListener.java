package com.siberanka.donutauctions.listener;

import com.siberanka.donutauctions.gui.AuctionMenuHolder;
import com.siberanka.donutauctions.gui.AuctionMenuService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class AuctionInventoryListener implements Listener {

    private final AuctionMenuService menuService;

    public AuctionInventoryListener(AuctionMenuService menuService) {
        this.menuService = menuService;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof AuctionMenuHolder)) {
            return;
        }

        // Secure default: block all interactions while custom menu is open.
        event.setCancelled(true);

        if (event.getClickedInventory() == null) {
            return;
        }
        if (event.getView().getTopInventory() != event.getClickedInventory()) {
            return;
        }

        boolean handled = menuService.handleClick(player, event.getClickedInventory(), event.getSlot(), event.getCurrentItem());
        if (!handled) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof AuctionMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= event.getView().getTopInventory().getSize()) {
                continue;
            }
            if (menuService.isQuickListSlot(holder.type(), rawSlot)) {
                menuService.handleQuickListFromCursor(player);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getView().getTopInventory().getHolder() instanceof AuctionMenuHolder) {
            menuService.onInventoryClosed(player.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        menuService.clearPlayerState(event.getPlayer().getUniqueId());
    }
}
