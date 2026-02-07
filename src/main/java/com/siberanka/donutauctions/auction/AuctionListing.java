package com.siberanka.donutauctions.auction;

import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.util.UUID;

public record AuctionListing(
        UUID id,
        UUID sellerUuid,
        String sellerName,
        ItemStack item,
        double price,
        Instant createdAt,
        Instant expiresAt
) {
    public AuctionListing copy() {
        return new AuctionListing(id, sellerUuid, sellerName, item.clone(), price, createdAt, expiresAt);
    }
}
