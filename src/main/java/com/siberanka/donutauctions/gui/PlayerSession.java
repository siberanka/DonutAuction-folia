package com.siberanka.donutauctions.gui;

import java.util.UUID;

public final class PlayerSession {

    private String searchText = "";
    private boolean awaitingSearchInput;
    private long lastActionAt;
    private UUID confirmListingId;

    public String searchText() {
        return searchText;
    }

    public void searchText(String searchText) {
        this.searchText = searchText == null ? "" : searchText;
    }

    public boolean awaitingSearchInput() {
        return awaitingSearchInput;
    }

    public void awaitingSearchInput(boolean awaitingSearchInput) {
        this.awaitingSearchInput = awaitingSearchInput;
    }

    public long lastActionAt() {
        return lastActionAt;
    }

    public void lastActionAt(long lastActionAt) {
        this.lastActionAt = lastActionAt;
    }

    public UUID confirmListingId() {
        return confirmListingId;
    }

    public void confirmListingId(UUID confirmListingId) {
        this.confirmListingId = confirmListingId;
    }
}
