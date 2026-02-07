package com.siberanka.donutauctions.gui;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import com.siberanka.donutauctions.auction.AuctionListing;
import com.siberanka.donutauctions.auction.AuctionService;
import com.siberanka.donutauctions.auction.TransactionRecord;
import com.siberanka.donutauctions.config.LanguageManager;
import com.siberanka.donutauctions.filter.FilterManager;
import com.siberanka.donutauctions.hook.EconomyHook;
import com.siberanka.donutauctions.hook.UltimateShopHook;
import com.siberanka.donutauctions.util.FormatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

public final class AuctionMenuService {

    private final DonutAuctionsPlugin plugin;
    private final LanguageManager lang;
    private final AuctionService auctionService;
    private final EconomyHook economy;
    private final UltimateShopHook ultimateShop;
    private final FilterManager filterManager;

    private final NamespacedKey listingKey;
    private final NamespacedKey actionKey;

    private final ConcurrentMap<UUID, String> searchState = new ConcurrentHashMap<>();
    private final Set<UUID> waitingSearch = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, String> sortState = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> filterState = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Long> clickThrottle = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> warnThrottle = new ConcurrentHashMap<>();

    public AuctionMenuService(DonutAuctionsPlugin plugin, LanguageManager lang, AuctionService auctionService, EconomyHook economy, UltimateShopHook ultimateShop, FilterManager filterManager) {
        this.plugin = plugin;
        this.lang = lang;
        this.auctionService = auctionService;
        this.economy = economy;
        this.ultimateShop = ultimateShop;
        this.filterManager = filterManager;
        this.listingKey = new NamespacedKey(plugin, "listing-id");
        this.actionKey = new NamespacedKey(plugin, "action");
    }

    public void clearState() {
        searchState.clear();
        waitingSearch.clear();
        sortState.clear();
        filterState.clear();
        clickThrottle.clear();
    }

    public void openAuction(Player player, int page) {
        String search = searchState.get(player.getUniqueId());
        openAuction(player, page, search);
    }

    public void openAuction(Player player, int page, String search) {
        searchState.put(player.getUniqueId(), search == null ? "" : search);

        int size = lang.number("gui.auction.size", 54);
        List<Integer> itemSlots = parseSlots(lang.rawString("gui.auction.item-slots", "0-44"));

        List<AuctionListing> source = new ArrayList<>(auctionService.activeListings(search));
        String filterMode = filterManager.normalizeFilter(filterState.getOrDefault(player.getUniqueId(), "all"));
        filterState.put(player.getUniqueId(), filterMode);
        source = source.stream()
                .filter(listing -> filterManager.matches(filterMode, listing.item()))
                .collect(Collectors.toCollection(ArrayList::new));

        String sortMode = sortState.getOrDefault(player.getUniqueId(), "newest");
        applySort(source, sortMode);

        int maxPage = Math.max(1, (int) Math.ceil(source.size() / (double) itemSlots.size()));
        int currentPage = Math.max(1, Math.min(page, maxPage));

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("page", String.valueOf(currentPage));
        placeholders.put("max_page", String.valueOf(maxPage));

        Inventory inventory = Bukkit.createInventory(new AuctionMenuHolder(MenuType.AUCTION, currentPage), size, lang.text("gui.auction.title", placeholders));
        fillBackground(inventory, "gui.auction.controls.filler");
        placeControls(inventory, "gui.auction.controls", placeholders);
        decorateAuctionControls(inventory, player, sortMode, filterMode);

        int from = (currentPage - 1) * itemSlots.size();
        int to = Math.min(source.size(), from + itemSlots.size());
        List<AuctionListing> pageEntries = source.subList(from, to);

        for (int i = 0; i < pageEntries.size(); i++) {
            AuctionListing listing = pageEntries.get(i);
            ItemStack icon = listing.item().clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta == null) {
                continue;
            }

            Duration left = Duration.between(Instant.now(), listing.expiresAt());
            Map<String, String> itemMap = new HashMap<>();
            itemMap.put("price", economy.format(listing.price()));
            itemMap.put("seller", listing.sellerName());
            itemMap.put("time_left", FormatUtil.humanDuration(left));

            List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
            if (!lore.isEmpty()) {
                lore.add(" ");
            }
            if (listing.sellerUuid().equals(player.getUniqueId())) {
                lore.addAll(lang.textList("gui.auction.own-listing-lore", itemMap));
            } else {
                lore.addAll(lang.textList("gui.auction.listing-lore", itemMap));
            }

            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, listing.id().toString());
            icon.setItemMeta(meta);
            inventory.setItem(itemSlots.get(i), icon);
        }

        player.openInventory(inventory);
    }

    public void openMyItems(Player player, int page) {
        List<AuctionListing> mine = auctionService.myListings(player.getUniqueId());

        int size = lang.number("gui.my-items.size", 27);
        List<Integer> itemSlots = parseSlots(lang.rawString("gui.my-items.item-slots", "0-17"));
        int maxPage = Math.max(1, (int) Math.ceil(mine.size() / (double) itemSlots.size()));
        int currentPage = Math.max(1, Math.min(page, maxPage));

        Map<String, String> placeholders = Map.of(
                "page", String.valueOf(currentPage),
                "max_page", String.valueOf(maxPage)
        );

        Inventory inventory = Bukkit.createInventory(new AuctionMenuHolder(MenuType.MY_ITEMS, currentPage), size, lang.text("gui.my-items.title", placeholders));
        fillBackground(inventory, "gui.my-items.controls.filler");
        placeControls(inventory, "gui.my-items.controls", placeholders);

        int from = (currentPage - 1) * itemSlots.size();
        int to = Math.min(mine.size(), from + itemSlots.size());
        List<AuctionListing> pageEntries = mine.subList(from, to);

        for (int i = 0; i < pageEntries.size(); i++) {
            AuctionListing listing = pageEntries.get(i);
            ItemStack icon = listing.item().clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta == null) {
                continue;
            }

            Duration left = Duration.between(Instant.now(), listing.expiresAt());
            Map<String, String> itemMap = Map.of(
                    "price", economy.format(listing.price()),
                    "seller", listing.sellerName(),
                    "time_left", FormatUtil.humanDuration(left)
            );
            List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
            if (!lore.isEmpty()) {
                lore.add(" ");
            }
            lore.addAll(lang.textList("gui.auction.own-listing-lore", itemMap));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(listingKey, PersistentDataType.STRING, listing.id().toString());
            icon.setItemMeta(meta);
            inventory.setItem(itemSlots.get(i), icon);
        }

        player.openInventory(inventory);
    }

    public void openTransactions(Player player, int page) {
        List<TransactionRecord> tx = auctionService.transactionsFor(player.getUniqueId());

        int size = lang.number("gui.transactions.size", 54);
        List<Integer> itemSlots = parseSlots(lang.rawString("gui.transactions.item-slots", "0-44"));
        int maxPage = Math.max(1, (int) Math.ceil(tx.size() / (double) itemSlots.size()));
        int currentPage = Math.max(1, Math.min(page, maxPage));

        Map<String, String> placeholders = Map.of(
                "page", String.valueOf(currentPage),
                "max_page", String.valueOf(maxPage)
        );

        Inventory inventory = Bukkit.createInventory(new AuctionMenuHolder(MenuType.TRANSACTIONS, currentPage), size, lang.text("gui.transactions.title", placeholders));
        fillBackground(inventory, "gui.transactions.controls.filler");
        placeControls(inventory, "gui.transactions.controls", placeholders);

        int from = (currentPage - 1) * itemSlots.size();
        int to = Math.min(tx.size(), from + itemSlots.size());
        List<TransactionRecord> pageEntries = tx.subList(from, to);

        for (int i = 0; i < pageEntries.size(); i++) {
            TransactionRecord record = pageEntries.get(i);
            boolean isBuyer = record.buyer().equals(player.getUniqueId());
            ItemStack icon = new ItemStack(isBuyer ? Material.LIME_STAINED_GLASS : Material.RED_STAINED_GLASS);
            ItemMeta meta = icon.getItemMeta();
            if (meta == null) {
                continue;
            }

            Map<String, String> map = Map.of(
                    "price", economy.format(record.price()),
                    "seller", record.sellerName(),
                    "buyer", record.buyerName()
            );
            meta.setDisplayName(lang.text(isBuyer ? "gui.transactions.entry-bought-name" : "gui.transactions.entry-sold-name", map));
            meta.setLore(lang.textList(isBuyer ? "gui.transactions.entry-bought-lore" : "gui.transactions.entry-sold-lore", map));
            icon.setItemMeta(meta);
            inventory.setItem(itemSlots.get(i), icon);
        }

        player.openInventory(inventory);
    }

    public boolean handleClick(Player player, Inventory inventory, int slot, ItemStack clicked) {
        if (!(inventory.getHolder() instanceof AuctionMenuHolder holder)) {
            return false;
        }

        if (slot < 0 || slot >= inventory.getSize()) {
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldown = Math.max(25L, plugin.getConfig().getLong("security.click-cooldown-ms", 80L));
        long last = clickThrottle.getOrDefault(player.getUniqueId(), 0L);
        if (now - last < cooldown) {
            return true;
        }
        clickThrottle.put(player.getUniqueId(), now);

        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) {
            return true;
        }

        try {
            ItemMeta meta = clicked.getItemMeta();
            String action = meta.getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
            if (action != null) {
                return handleAction(player, holder, action);
            }

            String listingIdRaw = meta.getPersistentDataContainer().get(listingKey, PersistentDataType.STRING);
            if (listingIdRaw == null) {
                return true;
            }

            UUID listingId = UUID.fromString(listingIdRaw);

            auctionService.get(listingId).ifPresent(listing -> {
                if (listing.sellerUuid().equals(player.getUniqueId())) {
                    boolean removed = auctionService.removeOwnListing(listing.id(), player.getUniqueId(), UUID.randomUUID().toString());
                    if (!removed) {
                        player.sendMessage(lang.text("messages.try-again"));
                        return;
                    }
                    giveOrDrop(player, listing.item().clone());
                    player.sendMessage(lang.text("messages.listing-removed", Map.of("item", listing.item().getType().name())));
                    openAuction(player, holder.page());
                    return;
                }

                if (!economy.isReady()) {
                    player.sendMessage(lang.text("messages.vault-required"));
                    return;
                }

                if (!economy.has(player, listing.price())) {
                    player.sendMessage(lang.text("messages.not-enough-money"));
                    return;
                }

                AuctionService.PurchaseResult result = auctionService.purchase(
                        listing.id(),
                        player,
                        economy,
                        player.getUniqueId() + ":" + listing.id() + ":" + System.nanoTime()
                );
                if (!result.success()) {
                    player.sendMessage(lang.text(result.messageKey()));
                    return;
                }

                AuctionListing bought = result.listing();
                giveOrDrop(player, bought.item().clone());
                plugin.ultimateShopHook().recordSale(bought.item());
                player.sendMessage(lang.text("messages.listing-bought", Map.of(
                        "item", bought.item().getType().name(),
                        "price", economy.format(bought.price())
                )));
                openAuction(player, holder.page());
            });
        } catch (Exception ex) {
            warnRateLimited("menu-click-ex", "Menu click failed safely: " + ex.getClass().getSimpleName());
            player.closeInventory();
            player.sendMessage(lang.text("messages.try-again"));
        }

        return true;
    }

    public void requestSearch(Player player) {
        waitingSearch.add(player.getUniqueId());
    }

    public boolean isWaitingSearch(UUID player) {
        return waitingSearch.contains(player);
    }

    public void applySearchInput(Player player, String input) {
        waitingSearch.remove(player.getUniqueId());
        if (input == null) {
            player.sendMessage(lang.text("messages.search-empty"));
            return;
        }

        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            player.sendMessage(lang.text("messages.search-empty"));
            return;
        }

        int maxSearchLength = auctionService.maxSearchLength();
        if (trimmed.length() > maxSearchLength) {
            trimmed = trimmed.substring(0, maxSearchLength);
        }

        if (trimmed.equalsIgnoreCase("clear")) {
            searchState.remove(player.getUniqueId());
            player.sendMessage(lang.text("messages.search-cleared"));
            openAuction(player, 1, "");
            return;
        }
        searchState.put(player.getUniqueId(), trimmed);
        player.sendMessage(lang.text("messages.search-set", Map.of("query", trimmed)));
        openAuction(player, 1, trimmed);
    }

    private boolean handleAction(Player player, AuctionMenuHolder holder, String action) {
        switch (action) {
            case "previous" -> {
                openByType(player, holder.type(), holder.page() - 1);
                return true;
            }
            case "next" -> {
                openByType(player, holder.type(), holder.page() + 1);
                return true;
            }
            case "search" -> {
                player.closeInventory();
                requestSearch(player);
                player.sendMessage(lang.text("messages.search-start"));
                return true;
            }
            case "sort" -> {
                cycleSort(player.getUniqueId());
                openAuction(player, holder.page());
                return true;
            }
            case "filter" -> {
                String current = filterState.getOrDefault(player.getUniqueId(), "all");
                String next = filterManager.nextFilter(current);
                filterState.put(player.getUniqueId(), next);
                player.sendMessage(lang.text("messages.filter-set", Map.of("filter", lang.text("filters.names." + next))));
                openAuction(player, holder.page());
                return true;
            }
            case "quick_list" -> {
                handleQuickListFromCursor(player);
                return true;
            }
            case "my_items" -> {
                openMyItems(player, 1);
                return true;
            }
            case "transactions" -> {
                openTransactions(player, 1);
                return true;
            }
            case "auction" -> {
                openAuction(player, 1);
                return true;
            }
            case "close" -> {
                player.closeInventory();
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    private void cycleSort(UUID player) {
        String current = sortState.getOrDefault(player, "newest");
        String next = switch (current) {
            case "newest" -> "oldest";
            case "oldest" -> "price_low";
            case "price_low" -> "price_high";
            default -> "newest";
        };
        sortState.put(player, next);
    }

    private void applySort(List<AuctionListing> list, String mode) {
        switch (mode) {
            case "oldest" -> list.sort(Comparator.comparing(AuctionListing::createdAt));
            case "price_low" -> list.sort(Comparator.comparingDouble(AuctionListing::price));
            case "price_high" -> list.sort(Comparator.comparingDouble(AuctionListing::price).reversed());
            default -> list.sort(Comparator.comparing(AuctionListing::createdAt).reversed());
        }
    }

    private void openByType(Player player, MenuType type, int page) {
        switch (type) {
            case AUCTION -> openAuction(player, page);
            case MY_ITEMS -> openMyItems(player, page);
            case TRANSACTIONS -> openTransactions(player, page);
        }
    }

    private void fillBackground(Inventory inventory, String fillerPath) {
        if (!lang.config().contains(fillerPath)) {
            return;
        }

        ItemStack filler = menuItem(fillerPath, Map.of(), null);
        int slot = lang.number(fillerPath + ".slot", -1);
        if (slot >= 0) {
            if (slot < inventory.getSize()) {
                inventory.setItem(slot, filler);
            } else {
                warnRateLimited("slot-oob", "Configured filler slot out of bounds: " + slot);
            }
            return;
        }

        List<Integer> slots = parseSlots(lang.rawString(fillerPath + ".slots", ""));
        for (int fillSlot : slots) {
            if (fillSlot >= 0 && fillSlot < inventory.getSize()) {
                inventory.setItem(fillSlot, filler);
            }
        }
    }

    private void placeControls(Inventory inventory, String basePath, Map<String, String> placeholders) {
        if (!lang.config().contains(basePath)) {
            return;
        }

        for (String key : lang.config().getConfigurationSection(basePath).getKeys(false)) {
            String path = basePath + "." + key;
            int slot = lang.number(path + ".slot", -1);
            if (slot < 0 || key.equals("filler")) {
                continue;
            }
            if (slot >= inventory.getSize()) {
                warnRateLimited("slot-oob", "Configured control slot out of bounds: " + slot + " for " + key);
                continue;
            }
            ItemStack item = menuItem(path, placeholders, key);
            inventory.setItem(slot, item);
        }
    }

    private ItemStack menuItem(String path, Map<String, String> placeholders, String action) {
        String materialName = lang.rawString(path + ".material", "BARRIER");
        Material material = Material.matchMaterial(materialName);
        if (material == null) {
            material = Material.BARRIER;
        }

        ItemStack item = new ItemStack(material, Math.max(1, lang.number(path + ".amount", 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(lang.text(path + ".name", placeholders));
            List<String> lore = lang.textList(path + ".lore", placeholders);
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            if (action != null) {
                meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private List<Integer> parseSlots(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<Integer> slots = new ArrayList<>();
        for (String token : raw.split(",")) {
            token = token.trim();
            try {
                if (token.contains("-")) {
                    String[] range = token.split("-");
                    int start = Integer.parseInt(range[0]);
                    int end = Integer.parseInt(range[1]);
                    for (int i = start; i <= end; i++) {
                        slots.add(i);
                    }
                } else {
                    slots.add(Integer.parseInt(token));
                }
            } catch (Exception ex) {
                warnRateLimited("slot-parse", "Invalid slot token in lang config: " + token);
            }
        }
        return slots.stream().distinct().sorted().collect(Collectors.toList());
    }

    public void clearPlayerState(UUID playerId) {
        searchState.remove(playerId);
        waitingSearch.remove(playerId);
        sortState.remove(playerId);
        filterState.remove(playerId);
        clickThrottle.remove(playerId);
    }

    public void onInventoryClosed(UUID playerId) {
        // Keep persistent session preferences/search text.
        clickThrottle.remove(playerId);
    }

    public boolean isQuickListSlot(MenuType type, int slot) {
        if (type != MenuType.MY_ITEMS) {
            return false;
        }
        return slot == lang.number("gui.my-items.controls.quick_list.slot", -1);
    }

    public void shutdown() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getOpenInventory().getTopInventory().getHolder() instanceof AuctionMenuHolder) {
                online.closeInventory();
            }
        }
        clearState();
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
        }
    }

    private void warnRateLimited(String key, String message) {
        long now = System.currentTimeMillis();
        long prev = warnThrottle.getOrDefault(key, 0L);
        if (now - prev < 5000L) {
            return;
        }
        warnThrottle.put(key, now);
        plugin.getLogger().warning(message);
    }

    public void handleQuickListFromCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR) {
            player.sendMessage(lang.text("messages.quick-list-hold-item"));
            return;
        }

        if (ultimateShop.isActive()
                && plugin.getConfig().getBoolean("ultimateshop.only-sellable-items", false)
                && !ultimateShop.isSellable(cursor, player)) {
            player.sendMessage(lang.text("messages.not-sellable-in-ultimateshop"));
            return;
        }

        double price;
        if (ultimateShop.isActive()) {
            var suggested = ultimateShop.suggestAuctionPrice(cursor, player);
            if (suggested.isEmpty()) {
                player.sendMessage(lang.text("messages.quick-list-no-price"));
                return;
            }
            price = suggested.getAsDouble();
        } else {
            player.sendMessage(lang.text("messages.quick-list-no-price"));
            return;
        }

        if (!Double.isFinite(price) || price <= 0D) {
            player.sendMessage(lang.text("messages.quick-list-no-price"));
            return;
        }

        ItemStack toList = cursor.clone();
        player.setItemOnCursor(new ItemStack(Material.AIR));
        Optional<AuctionListing> out = auctionService.createListing(player, toList, price);
        if (out.isEmpty()) {
            ItemStack currentCursor = player.getItemOnCursor();
            if (currentCursor == null || currentCursor.getType() == Material.AIR) {
                player.setItemOnCursor(toList);
            } else {
                giveOrDrop(player, toList);
            }
            player.sendMessage(lang.text("messages.max-listings-reached"));
            return;
        }

        player.sendMessage(lang.text("messages.quick-list-success", Map.of("price", economy.format(price))));
        openMyItems(player, 1);
    }

    private void decorateAuctionControls(Inventory inventory, Player player, String sortMode, String filterMode) {
        int sortSlot = lang.number("gui.auction.controls.sort.slot", 48);
        int filterSlot = lang.number("gui.auction.controls.filter.slot", 47);

        if (sortSlot >= 0 && sortSlot < inventory.getSize()) {
            ItemStack sortItem = menuItem(
                    "gui.auction.controls.sort",
                    Map.of("sort_name", lang.text("gui.sort-modes." + sortMode)),
                    "sort"
            );
            ItemMeta meta = sortItem.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
                for (String mode : List.of("newest", "oldest", "price_low", "price_high")) {
                    String key = mode.equals(sortMode) ? "gui.auction.sort-active-line" : "gui.auction.sort-inactive-line";
                    lore.add(lang.text(key, Map.of("name", lang.text("gui.sort-modes." + mode))));
                }
                meta.setLore(lore);
                sortItem.setItemMeta(meta);
            }
            inventory.setItem(sortSlot, sortItem);
        }

        if (filterSlot >= 0 && filterSlot < inventory.getSize()) {
            ItemStack filterItem = menuItem(
                    "gui.auction.controls.filter",
                    Map.of("filter_name", lang.text("filters.names." + filterMode)),
                    "filter"
            );
            ItemMeta meta = filterItem.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>(meta.hasLore() ? meta.getLore() : List.of());
                for (String id : filterManager.order()) {
                    String key = id.equals(filterMode) ? "gui.auction.filter-active-line" : "gui.auction.filter-inactive-line";
                    lore.add(lang.text(key, Map.of("name", lang.text("filters.names." + id))));
                }
                meta.setLore(lore);
                filterItem.setItemMeta(meta);
            }
            inventory.setItem(filterSlot, filterItem);
        }
    }
}
