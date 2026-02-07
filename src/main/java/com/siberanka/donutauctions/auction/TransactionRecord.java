package com.siberanka.donutauctions.auction;

import java.time.Instant;
import java.util.UUID;

public record TransactionRecord(
        UUID auctionId,
        UUID buyer,
        String buyerName,
        UUID seller,
        String sellerName,
        double price,
        Instant at
) {
}