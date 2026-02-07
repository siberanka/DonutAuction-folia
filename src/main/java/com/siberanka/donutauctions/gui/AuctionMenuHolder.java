package com.siberanka.donutauctions.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class AuctionMenuHolder implements InventoryHolder {

    private final MenuType type;
    private final int page;

    public AuctionMenuHolder(MenuType type, int page) {
        this.type = type;
        this.page = page;
    }

    public MenuType type() {
        return type;
    }

    public int page() {
        return page;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
