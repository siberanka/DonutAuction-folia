package com.siberanka.donutauctions.auction;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import com.siberanka.donutauctions.hook.EconomyHook;
import com.siberanka.donutauctions.util.AtomicFileUtil;
import com.siberanka.donutauctions.util.SchedulerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class AuctionService {

    public record PurchaseResult(boolean success, String messageKey, AuctionListing listing, TransactionRecord transaction) {
        public static PurchaseResult fail(String key) {
            return new PurchaseResult(false, key, null, null);
        }

        public static PurchaseResult ok(AuctionListing listing, TransactionRecord transaction) {
            return new PurchaseResult(true, "messages.listing-bought", listing, transaction);
        }
    }

    private final DonutAuctionsPlugin plugin;
    private final ConcurrentMap<UUID, AuctionListing> listings = new ConcurrentHashMap<>();
    private final List<TransactionRecord> transactions = new ArrayList<>();
    private final ConcurrentMap<String, Long> processedOperations = new ConcurrentHashMap<>();

    private int autosaveTaskId = -1;
    private Object foliaAutosaveTask;
    private int dynamicRepriceTaskId = -1;
    private Object foliaDynamicRepriceTask;
    private long operationTtlMillis = TimeUnit.MINUTES.toMillis(10);
    private final Object mutex = new Object();

    public AuctionService(DonutAuctionsPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = dataFile();
        if (!file.exists()) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        if (yaml.getInt("schema-version", 0) != 1 || !yaml.getBoolean("commit-marker", false)) {
            var restored = AtomicFileUtil.restoreLatestBackup(file.toPath(), backupDir().toPath());
            if (restored.isEmpty()) {
                return;
            }
            plugin.getLogger().warning("Recovered auction data from backup: " + restored.get().getFileName());
            yaml = YamlConfiguration.loadConfiguration(file);
            if (yaml.getInt("schema-version", 0) != 1 || !yaml.getBoolean("commit-marker", false)) {
                return;
            }
        }

        synchronized (mutex) {
            listings.clear();
            transactions.clear();

            ConfigurationSection listingsSection = yaml.getConfigurationSection("listings");
            if (listingsSection != null) {
                for (String key : listingsSection.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(key);
                        ConfigurationSection section = listingsSection.getConfigurationSection(key);
                        if (section == null) {
                            continue;
                        }

                        ItemStack item = section.getItemStack("item");
                        if (item == null || item.getType() == Material.AIR) {
                            continue;
                        }

                        UUID seller = UUID.fromString(section.getString("seller-uuid", ""));
                        AuctionListing listing = new AuctionListing(
                                id,
                                seller,
                                section.getString("seller-name", "unknown"),
                                item,
                                section.getDouble("price", 0.0D),
                                Instant.ofEpochMilli(section.getLong("created-at", System.currentTimeMillis())),
                                Instant.ofEpochMilli(section.getLong("expires-at", System.currentTimeMillis()))
                        );
                        if (!listing.expiresAt().isBefore(Instant.now())) {
                            listings.put(id, listing);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            List<Map<?, ?>> txList = yaml.getMapList("transactions");
            for (Map<?, ?> row : txList) {
                try {
                    TransactionRecord tx = new TransactionRecord(
                            UUID.fromString(String.valueOf(row.get("auction-id"))),
                            UUID.fromString(String.valueOf(row.get("buyer"))),
                            String.valueOf(row.get("buyer-name")),
                            UUID.fromString(String.valueOf(row.get("seller"))),
                            String.valueOf(row.get("seller-name")),
                            Double.parseDouble(String.valueOf(row.get("price"))),
                            Instant.ofEpochMilli(Long.parseLong(String.valueOf(row.get("at"))))
                    );
                    transactions.add(tx);
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void startAutoSave() {
        if (autosaveTaskId != -1 || foliaAutosaveTask != null) {
            return;
        }
        long period = Math.max(20L, plugin.getConfig().getLong("storage.autosave-ticks", 1200L));
        if (!scheduleWithFoliaAsync(period)) {
            autosaveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::saveSafely, period, period).getTaskId();
        }
    }

    public void startDynamicRepricing() {
        if (dynamicRepriceTaskId != -1 || foliaDynamicRepriceTask != null) {
            return;
        }
        if (!shouldUseDynamicRepricing()) {
            return;
        }
        if (!plugin.ultimateShopHook().isActive()) {
            return;
        }

        long periodTicks = Math.max(20L, dynamicRepriceIntervalSeconds() * 20L);
        if (!scheduleDynamicWithFoliaAsync(periodTicks)) {
            dynamicRepriceTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    () -> SchedulerAdapter.runSync(plugin, this::refreshAllListingPricesSafe),
                    periodTicks,
                    periodTicks
            ).getTaskId();
        }
    }

    public void shutdown() {
        cancelFoliaAutosave();
        if (autosaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autosaveTaskId);
            autosaveTaskId = -1;
        }
        cancelDynamicReprice();
        saveSafely();
        synchronized (mutex) {
            listings.clear();
            transactions.clear();
        }
        processedOperations.clear();
    }

    public void reloadRuntimeSchedulers() {
        cancelFoliaAutosave();
        if (autosaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autosaveTaskId);
            autosaveTaskId = -1;
        }
        cancelDynamicReprice();
        startAutoSave();
        startDynamicRepricing();
    }

    public synchronized Optional<AuctionListing> createListing(Player seller, ItemStack item, double price) {
        if (seller == null || item == null || item.getType() == Material.AIR || item.getAmount() <= 0) {
            return Optional.empty();
        }
        double normalizedPrice = roundCurrency(price);
        if (!Double.isFinite(normalizedPrice) || normalizedPrice <= 0D) {
            return Optional.empty();
        }
        if (!isMetaSafe(item.getItemMeta())) {
            return Optional.empty();
        }

        int max = plugin.getConfig().getInt(
                "auction.limits.max-active-per-player",
                plugin.getConfig().getInt("auction.max-listings-per-player", 20)
        );
        long current = listings.values().stream()
                .filter(listing -> listing.sellerUuid().equals(seller.getUniqueId()))
                .count();
        if (current >= max) {
            return Optional.empty();
        }

        int durationHours = plugin.getConfig().getInt(
                "auction.listing-duration-hours",
                plugin.getConfig().getInt("auction.default-duration-hours", 24)
        );
        AuctionListing listing = new AuctionListing(
                UUID.randomUUID(),
                seller.getUniqueId(),
                seller.getName(),
                item.clone(),
                normalizedPrice,
                Instant.now(),
                Instant.now().plus(Duration.ofHours(durationHours))
        );
        listings.put(listing.id(), listing);
        return Optional.of(listing);
    }

    public synchronized PurchaseResult purchase(UUID listingId, Player buyer, EconomyHook economy, String operationId) {
        if (operationSeen(operationId)) {
            return PurchaseResult.fail("messages.try-again");
        }

        if (shouldRefreshBeforePurchase() && plugin.ultimateShopHook().isActive()) {
            refreshListingPriceForPurchase(listingId, buyer);
        }

        AuctionListing listing = listings.get(listingId);
        if (listing == null || listing.expiresAt().isBefore(Instant.now())) {
            listings.remove(listingId);
            return PurchaseResult.fail("messages.listing-not-found");
        }

        if (listing.sellerUuid().equals(buyer.getUniqueId())) {
            return PurchaseResult.fail("messages.cannot-buy-own-item");
        }
        if (!economy.isReady()) {
            return PurchaseResult.fail("messages.vault-required");
        }
        if (!economy.has(buyer, listing.price())) {
            return PurchaseResult.fail("messages.not-enough-money");
        }

        if (!economy.withdraw(buyer, listing.price())) {
            return PurchaseResult.fail("messages.not-enough-money");
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.sellerUuid());
        if (!economy.deposit(seller, listing.price())) {
            economy.deposit(buyer, listing.price());
            return PurchaseResult.fail("messages.economy-failed");
        }

        AuctionListing removed = listings.remove(listingId);
        if (removed == null) {
            economy.deposit(buyer, listing.price());
            economy.withdraw(seller, listing.price());
            return PurchaseResult.fail("messages.try-again");
        }

        TransactionRecord tx = new TransactionRecord(
                removed.id(),
                buyer.getUniqueId(),
                buyer.getName(),
                removed.sellerUuid(),
                removed.sellerName(),
                removed.price(),
                Instant.now()
        );
        addTransaction(tx);
        markOperation(operationId);
        return PurchaseResult.ok(removed, tx);
    }

    public synchronized boolean removeOwnListing(UUID listingId, UUID ownerUuid, String operationId) {
        if (operationSeen(operationId)) {
            return false;
        }
        AuctionListing listing = listings.get(listingId);
        if (listing == null || !listing.sellerUuid().equals(ownerUuid)) {
            return false;
        }
        AuctionListing removed = listings.remove(listingId);
        if (removed == null) {
            return false;
        }
        markOperation(operationId);
        return true;
    }

    public synchronized List<AuctionListing> activeListings(String query) {
        cleanupExpired();
        String search = query == null ? "" : query.toLowerCase(Locale.ROOT);
        return listings.values().stream()
                .filter(listing -> search.isBlank()
                        || listing.item().getType().name().toLowerCase(Locale.ROOT).contains(search)
                        || listing.sellerName().toLowerCase(Locale.ROOT).contains(search))
                .sorted(Comparator.comparing(AuctionListing::createdAt).reversed())
                .map(AuctionListing::copy)
                .toList();
    }

    public synchronized List<AuctionListing> myListings(UUID owner) {
        cleanupExpired();
        return listings.values().stream()
                .filter(listing -> listing.sellerUuid().equals(owner))
                .sorted(Comparator.comparing(AuctionListing::createdAt).reversed())
                .map(AuctionListing::copy)
                .toList();
    }

    public synchronized Optional<AuctionListing> get(UUID id) {
        cleanupExpired();
        AuctionListing listing = listings.get(id);
        return listing == null ? Optional.empty() : Optional.of(listing.copy());
    }

    public synchronized Optional<AuctionListing> remove(UUID id) {
        AuctionListing removed = listings.remove(id);
        return removed == null ? Optional.empty() : Optional.of(removed.copy());
    }

    public synchronized void addTransaction(TransactionRecord record) {
        transactions.add(0, record);
        int max = plugin.getConfig().getInt("auction.transactions-max", 200);
        if (transactions.size() > max) {
            transactions.subList(max, transactions.size()).clear();
        }
    }

    public synchronized List<TransactionRecord> transactionsFor(UUID player) {
        return transactions.stream()
                .filter(record -> record.buyer().equals(player) || record.seller().equals(player))
                .toList();
    }

    public synchronized int maxSearchLength() {
        return Math.max(8, plugin.getConfig().getInt("auction.max-search-length", 32));
    }

    private synchronized void cleanupExpired() {
        Instant now = Instant.now();
        listings.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private void saveSafely() {
        try {
            saveNow();
        } catch (Exception ex) {
            plugin.getLogger().warning("Auction data save failed safely: " + ex.getClass().getSimpleName());
        }
    }

    private synchronized void saveNow() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 1);
        yaml.set("updated-at", System.currentTimeMillis());

        for (Map.Entry<UUID, AuctionListing> entry : listings.entrySet()) {
            String base = "listings." + entry.getKey();
            AuctionListing listing = entry.getValue();
            yaml.set(base + ".seller-uuid", listing.sellerUuid().toString());
            yaml.set(base + ".seller-name", listing.sellerName());
            yaml.set(base + ".item", listing.item());
            yaml.set(base + ".price", listing.price());
            yaml.set(base + ".created-at", listing.createdAt().toEpochMilli());
            yaml.set(base + ".expires-at", listing.expiresAt().toEpochMilli());
        }

        List<Map<String, Object>> serializedTx = new ArrayList<>();
        for (TransactionRecord tx : transactions) {
            Map<String, Object> row = new HashMap<>();
            row.put("auction-id", tx.auctionId().toString());
            row.put("buyer", tx.buyer().toString());
            row.put("buyer-name", tx.buyerName());
            row.put("seller", tx.seller().toString());
            row.put("seller-name", tx.sellerName());
            row.put("price", tx.price());
            row.put("at", tx.at().toEpochMilli());
            serializedTx.add(row);
        }
        yaml.set("transactions", serializedTx);
        yaml.set("commit-marker", true);

        File file = dataFile();
        AtomicFileUtil.rotateBackups(file.toPath(), backupDir().toPath(), Math.max(2, plugin.getConfig().getInt("storage.backup-keep", 5)));
        AtomicFileUtil.saveYamlAtomically(file.toPath(), yaml);
    }

    private boolean operationSeen(String key) {
        pruneOperations();
        return processedOperations.containsKey(key);
    }

    private void markOperation(String key) {
        processedOperations.put(key, System.currentTimeMillis());
    }

    private void pruneOperations() {
        long now = System.currentTimeMillis();
        processedOperations.entrySet().removeIf(e -> now - e.getValue() > operationTtlMillis);
    }

    private File dataFile() {
        return new File(plugin.getDataFolder(), "auction-data.yml");
    }

    private File backupDir() {
        return new File(plugin.getDataFolder(), "backups");
    }

    @SuppressWarnings("unchecked")
    private boolean scheduleWithFoliaAsync(long periodTicks) {
        try {
            Object asyncScheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            long periodMillis = Math.max(50L, periodTicks * 50L);
            Consumer<Object> consumer = ignored -> saveSafely();

            try {
                Object task = asyncScheduler.getClass().getMethod(
                                "runAtFixedRate",
                                org.bukkit.plugin.Plugin.class,
                                Consumer.class,
                                long.class,
                                long.class,
                                TimeUnit.class
                        )
                        .invoke(asyncScheduler, plugin, consumer, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
                foliaAutosaveTask = task;
                return true;
            } catch (NoSuchMethodException ignored) {
                Object task = asyncScheduler.getClass().getMethod(
                                "runAtFixedRate",
                                org.bukkit.plugin.Plugin.class,
                                Consumer.class,
                                Duration.class,
                                Duration.class
                        )
                        .invoke(asyncScheduler, plugin, consumer, Duration.ofMillis(periodMillis), Duration.ofMillis(periodMillis));
                foliaAutosaveTask = task;
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void cancelFoliaAutosave() {
        if (foliaAutosaveTask == null) {
            return;
        }
        try {
            foliaAutosaveTask.getClass().getMethod("cancel").invoke(foliaAutosaveTask);
        } catch (Throwable ignored) {
        } finally {
            foliaAutosaveTask = null;
        }
    }

    private void cancelDynamicReprice() {
        if (dynamicRepriceTaskId != -1) {
            Bukkit.getScheduler().cancelTask(dynamicRepriceTaskId);
            dynamicRepriceTaskId = -1;
        }
        if (foliaDynamicRepriceTask != null) {
            try {
                foliaDynamicRepriceTask.getClass().getMethod("cancel").invoke(foliaDynamicRepriceTask);
            } catch (Throwable ignored) {
            } finally {
                foliaDynamicRepriceTask = null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean scheduleDynamicWithFoliaAsync(long periodTicks) {
        try {
            Object asyncScheduler = Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer());
            long periodMillis = Math.max(50L, periodTicks * 50L);
            Consumer<Object> consumer = ignored -> SchedulerAdapter.runSync(plugin, this::refreshAllListingPricesSafe);

            try {
                Object task = asyncScheduler.getClass().getMethod(
                                "runAtFixedRate",
                                org.bukkit.plugin.Plugin.class,
                                Consumer.class,
                                long.class,
                                long.class,
                                TimeUnit.class
                        )
                        .invoke(asyncScheduler, plugin, consumer, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
                foliaDynamicRepriceTask = task;
                return true;
            } catch (NoSuchMethodException ignored) {
                Object task = asyncScheduler.getClass().getMethod(
                                "runAtFixedRate",
                                org.bukkit.plugin.Plugin.class,
                                Consumer.class,
                                Duration.class,
                                Duration.class
                        )
                        .invoke(asyncScheduler, plugin, consumer, Duration.ofMillis(periodMillis), Duration.ofMillis(periodMillis));
                foliaDynamicRepriceTask = task;
                return true;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private long dynamicRepriceIntervalSeconds() {
        return Math.max(5L, plugin.getConfig().getLong("ultimateshop.dynamic-repricing.interval-seconds", 300L));
    }

    private boolean shouldUseDynamicRepricing() {
        return plugin.getConfig().getBoolean("ultimateshop.dynamic-repricing.enabled", true);
    }

    private boolean shouldRefreshBeforePurchase() {
        return plugin.getConfig().getBoolean("ultimateshop.dynamic-repricing.refresh-before-purchase", true);
    }

    private synchronized void refreshListingPriceForPurchase(UUID listingId, Player buyer) {
        AuctionListing listing = listings.get(listingId);
        if (listing == null) {
            return;
        }
        double newPrice = plugin.ultimateShopHook().suggestAuctionPrice(listing.item(), buyer).orElse(0D);
        if (!Double.isFinite(newPrice) || newPrice <= 0D) {
            return;
        }
        double rounded = roundCurrency(newPrice);
        if (rounded <= 0D || Math.abs(rounded - listing.price()) < 0.0001D) {
            return;
        }
        AuctionListing updated = new AuctionListing(
                listing.id(),
                listing.sellerUuid(),
                listing.sellerName(),
                listing.item().clone(),
                rounded,
                listing.createdAt(),
                listing.expiresAt()
        );
        listings.put(listing.id(), updated);
    }

    private void refreshAllListingPricesSafe() {
        try {
            refreshAllListingPrices();
        } catch (Exception ex) {
            plugin.getLogger().warning("Dynamic repricing failed safely: " + ex.getClass().getSimpleName());
        }
    }

    private synchronized void refreshAllListingPrices() {
        if (!shouldUseDynamicRepricing() || !plugin.ultimateShopHook().isActive()) {
            return;
        }
        if (listings.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, AuctionListing> entry : listings.entrySet()) {
            AuctionListing listing = entry.getValue();
            double newPrice = plugin.ultimateShopHook().suggestAuctionPrice(listing.item()).orElse(0D);
            if (!Double.isFinite(newPrice) || newPrice <= 0D) {
                continue;
            }
            double rounded = roundCurrency(newPrice);
            if (rounded <= 0D || Math.abs(rounded - listing.price()) < 0.0001D) {
                continue;
            }
            AuctionListing updated = new AuctionListing(
                    listing.id(),
                    listing.sellerUuid(),
                    listing.sellerName(),
                    listing.item().clone(),
                    rounded,
                    listing.createdAt(),
                    listing.expiresAt()
            );
            listings.put(listing.id(), updated);
        }
    }

    private boolean isMetaSafe(ItemMeta meta) {
        if (meta == null) {
            return true;
        }
        int maxNameLength = Math.max(16, plugin.getConfig().getInt("security.max-display-name-length", 96));
        int maxLoreLines = Math.max(4, plugin.getConfig().getInt("security.max-lore-lines", 20));
        int maxLoreLineLength = Math.max(16, plugin.getConfig().getInt("security.max-lore-line-length", 160));
        int maxPdcKeys = Math.max(2, plugin.getConfig().getInt("security.max-pdc-keys", 16));

        if (meta.hasDisplayName() && meta.getDisplayName().length() > maxNameLength) {
            return false;
        }
        if (meta.hasLore()) {
            List<String> lore = meta.getLore();
            if (lore != null) {
                if (lore.size() > maxLoreLines) {
                    return false;
                }
                for (String line : lore) {
                    if (line != null && line.length() > maxLoreLineLength) {
                        return false;
                    }
                }
            }
        }
        return meta.getPersistentDataContainer().getKeys().size() <= maxPdcKeys;
    }

    private double roundCurrency(double value) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
