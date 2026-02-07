package com.siberanka.donutauctions.auction;

public record AuctionActionResult(boolean success, String messageKey) {

    public static AuctionActionResult ok(String messageKey) {
        return new AuctionActionResult(true, messageKey);
    }

    public static AuctionActionResult fail(String messageKey) {
        return new AuctionActionResult(false, messageKey);
    }
}