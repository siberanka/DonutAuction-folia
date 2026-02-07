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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        ItemStack unit = item.clone();
        unit.setAmount(1);
        return findSellPrice(unit, player).orElse(0D) > 0D;
    }

    public double calculateRecommendedAuctionPrice(ItemStack item, org.bukkit.entity.Player player) {
        if (item == null || item.getType() == Material.AIR || player == null) {
            return 0D;
        }

        int quantity = Math.max(1, item.getAmount());
        ItemStack unitItem = item.clone();
        unitItem.setAmount(1);

        double unitSellPrice = findSellPrice(unitItem, player).orElseGet(() -> fallbackMaterialSellPrice(unitItem.getType()));
        if (!Double.isFinite(unitSellPrice) || unitSellPrice <= 0D) {
            return 0D;
        }

        // Per-item plain value.
        double plainAuctionValueUnit = unitSellPrice * Math.max(0D, baseAuctionMultiplier);
        double enchantBonusTotalUnit = 0D;

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
                    // Each enchantment bonus is calculated independently from plain per-item value.
                    enchantBonusTotalUnit += (plainAuctionValueUnit * perLevel);
                }
            }
        }

        double unitFinal = Math.max(0D, plainAuctionValueUnit + enchantBonusTotalUnit);
        return roundCurrency(unitFinal * quantity);
    }

    public void recordSale(ItemStack item, int amount) {
        if (!integrationAvailable || !increaseDynamicSales || item == null || item.getType() == Material.AIR || amount <= 0) {
            return;
        }

        try {
            Class<?> clazz = loadShopHelperClass();
            if (clazz == null) {
                return;
            }
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
            Class<?> clazz = loadShopHelperClass();
            if (clazz == null) {
                return OptionalDouble.empty();
            }

            // UltimateShop 4.2.3+: getSellPrices(ItemStack[], Player, int) -> GiveResult
            Method arrayMethod = findMethod(clazz, "getSellPrices", ItemStack[].class, org.bukkit.entity.Player.class, int.class);
            if (arrayMethod != null) {
                Object out = arrayMethod.invoke(null, new ItemStack[]{item.clone()}, player, 1);
                OptionalDouble extracted = extractGiveResultPrice(out);
                if (extracted.isPresent()) {
                    return extracted;
                }
            }

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

    private double fallbackMaterialSellPrice(Material material) {
        String id = material.name();
        if (plugin.getConfig().isSet("ultimateshop.material-base-prices." + id)) {
            return plugin.getConfig().getDouble("ultimateshop.material-base-prices." + id, 0D);
        }
        return plugin.getConfig().getDouble("ultimateshop.fallback-material-prices." + id, 0D);
    }

    private double roundCurrency(double value) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
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

    private Class<?> loadShopHelperClass() {
        try {
            return Class.forName("cn.superiormc.ultimateshop.api.ShopHelper");
        } catch (ClassNotFoundException ignored) {
        }
        try {
            return Class.forName("cn.superiormc.ultimateshop.api.shop.ShopHelper");
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private OptionalDouble extractGiveResultPrice(Object giveResult) {
        if (giveResult == null) {
            return OptionalDouble.empty();
        }
        try {
            Method getResultMap = giveResult.getClass().getMethod("getResultMap");
            Object mapObj = getResultMap.invoke(giveResult);
            if (!(mapObj instanceof Map<?, ?> map) || map.isEmpty()) {
                return OptionalDouble.empty();
            }

            double economyTotal = 0D;
            double fallbackTotal = 0D;

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object value = entry.getValue();
                double amount = 0D;
                if (value instanceof BigDecimal decimal) {
                    amount = decimal.doubleValue();
                } else if (value instanceof Number number) {
                    amount = number.doubleValue();
                }
                if (amount <= 0D || !Double.isFinite(amount)) {
                    continue;
                }
                fallbackTotal += amount;

                // Prefer economy-like thing types when available.
                if (key != null) {
                    try {
                        Object typeObj = key.getClass().getField("type").get(key);
                        if (typeObj != null) {
                            String typeName = String.valueOf(typeObj);
                            if (typeName.contains("ECONOMY")) {
                                economyTotal += amount;
                            }
                        }
                    } catch (ReflectiveOperationException ignored) {
                    }
                }
            }

            double chosen = economyTotal > 0D ? economyTotal : fallbackTotal;
            return chosen > 0D ? OptionalDouble.of(chosen) : OptionalDouble.empty();
        } catch (ReflectiveOperationException ex) {
            return OptionalDouble.empty();
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
