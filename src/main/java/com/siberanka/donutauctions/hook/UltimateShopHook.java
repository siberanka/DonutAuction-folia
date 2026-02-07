package com.siberanka.donutauctions.hook;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.regex.Pattern;

public final class UltimateShopHook {
    private static final Pattern SAFE_MATERIAL = Pattern.compile("[^A-Z0-9_]");
    private static final Pattern BLOCKED_COMMAND_PATTERN = Pattern.compile("[\\r\\n`|&;]");

    private final DonutAuctionsPlugin plugin;
    private boolean configuredEnabled;
    private boolean increaseDynamicSales;
    private double baseAuctionMultiplier;
    private boolean integrationAvailable;
    private Plugin hooked;

    public UltimateShopHook(DonutAuctionsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        configuredEnabled = plugin.getConfig().getBoolean("ultimateshop.enabled", true);
        increaseDynamicSales = plugin.getConfig().getBoolean("ultimateshop.increase-dynamic-sales",
                plugin.getConfig().getBoolean("ultimateshop.increase-dynamic-price-on-auction-sale", false));
        baseAuctionMultiplier = plugin.getConfig().getDouble("ultimateshop.base-price-multiplier",
                plugin.getConfig().getDouble("ultimateshop.base-auction-multiplier", 1.0D));
        hooked = Bukkit.getPluginManager().getPlugin("UltimateShop");
        integrationAvailable = configuredEnabled && hooked != null && hooked.isEnabled();
    }

    public boolean isActive() {
        return integrationAvailable;
    }

    public OptionalDouble suggestAuctionPrice(ItemStack item) {
        org.bukkit.entity.Player anyPlayer = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (anyPlayer == null) {
            return OptionalDouble.empty();
        }
        double value = calculateRecommendedAuctionPrice(item, anyPlayer);
        return value > 0D ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    public OptionalDouble suggestAuctionPrice(ItemStack item, org.bukkit.entity.Player player) {
        double value = calculateRecommendedAuctionPrice(item, player);
        return value > 0D ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    public boolean isSellable(ItemStack item, org.bukkit.entity.Player player) {
        return findSellPrice(item, player).orElse(0D) > 0D;
    }

    public double calculateRecommendedAuctionPrice(ItemStack item, org.bukkit.entity.Player player) {
        double baseSellPrice = findSellPrice(item, player).orElseGet(() -> {
            String material = item.getType().name();
            if (plugin.getConfig().isSet("ultimateshop.material-base-prices." + material)) {
                return plugin.getConfig().getDouble("ultimateshop.material-base-prices." + material);
            }
            return plugin.getConfig().getDouble("ultimateshop.fallback-material-prices." + material, 0D);
        });
        if (baseSellPrice <= 0D) {
            return 0D;
        }

        // Plain (non-enchanted) auction value.
        double plainAuctionValue = baseSellPrice * Math.max(0D, baseAuctionMultiplier);
        double enchantBonusTotal = 0D;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("ultimateshop.enchantment-multipliers");
            if (section != null) {
                for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
                    String namespaced = entry.getKey().getKey().toString();
                    String plain = entry.getKey().getKey().getKey();
                    ConfigurationSection enchSection = section.getConfigurationSection(namespaced);
                    if (enchSection == null) {
                        enchSection = section.getConfigurationSection(plain);
                    }
                    if (enchSection == null) {
                        continue;
                    }
                    double perLevel = enchSection.getDouble(String.valueOf(entry.getValue()), 0D);
                    // Each enchantment bonus is calculated independently from plain value.
                    enchantBonusTotal += (plainAuctionValue * perLevel);
                }
            }
        }

        return Math.max(0D, plainAuctionValue + enchantBonusTotal);
    }

    public void recordSale(ItemStack item, int amount) {
        if (!integrationAvailable || !increaseDynamicSales || item == null || item.getType() == Material.AIR || amount <= 0) {
            return;
        }

        try {
            Class<?> clazz = Class.forName("cn.superiormc.ultimateshop.api.shop.ShopHelper");
            Method method = findMethod(clazz, "increaseSoldAmount", ItemStack.class, int.class);
            if (method != null) {
                method.invoke(null, item, amount);
            }
        } catch (ReflectiveOperationException ignored) {
            // Soft dependency should never break plugin runtime.
        }

        runDynamicSaleCommands(item.getType(), amount);
    }

    public void recordSale(ItemStack item) {
        if (item == null) {
            return;
        }
        recordSale(item, Math.max(1, item.getAmount()));
    }

    private OptionalDouble findSellPrice(ItemStack item, org.bukkit.entity.Player player) {
        if (!integrationAvailable || item == null || item.getType() == Material.AIR) {
            return OptionalDouble.empty();
        }

        try {
            Class<?> clazz = Class.forName("cn.superiormc.ultimateshop.api.shop.ShopHelper");

            Method singleMethod = findMethod(clazz, "getSellPrice", ItemStack.class, org.bukkit.entity.Player.class);
            if (singleMethod != null) {
                Object out = singleMethod.invoke(null, item, player);
                if (out instanceof Number number) {
                    return OptionalDouble.of(number.doubleValue());
                }
            }

            Method listMethod = findMethod(clazz, "getSellPrices", List.class, org.bukkit.entity.Player.class, int.class);
            if (listMethod != null) {
                Object out = listMethod.invoke(null, List.of(item), player, 1);
                if (out instanceof Number number) {
                    return OptionalDouble.of(number.doubleValue());
                }
            }
        } catch (ReflectiveOperationException ignored) {
            return OptionalDouble.empty();
        }

        return OptionalDouble.empty();
    }

    private Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method method = owner.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            return null;
        }
    }

    private void runDynamicSaleCommands(Material material, int amount) {
        if (!plugin.getConfig().getBoolean("ultimateshop.dynamic-sale-command.enabled", false)) {
            return;
        }

        List<String> commands = plugin.getConfig().getStringList("ultimateshop.dynamic-sale-command.commands");
        if (commands.isEmpty()) {
            return;
        }

        String safeMaterial = SAFE_MATERIAL.matcher(material.name()).replaceAll("");
        String amountText = String.valueOf(Math.max(1, amount));

        for (String raw : commands) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            String command = raw
                    .replace("%material%", safeMaterial)
                    .replace("%amount%", amountText)
                    .trim();

            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            if (command.isBlank()) {
                continue;
            }
            if (BLOCKED_COMMAND_PATTERN.matcher(command).find()) {
                plugin.getLogger().warning("Blocked unsafe dynamic-sale command pattern for material " + safeMaterial);
                continue;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }
}
