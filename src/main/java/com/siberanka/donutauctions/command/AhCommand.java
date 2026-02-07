package com.siberanka.donutauctions.command;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import com.siberanka.donutauctions.auction.AuctionListing;
import com.siberanka.donutauctions.auction.AuctionService;
import com.siberanka.donutauctions.config.LanguageManager;
import com.siberanka.donutauctions.gui.AuctionMenuService;
import com.siberanka.donutauctions.hook.EconomyHook;
import com.siberanka.donutauctions.hook.UltimateShopHook;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Locale;

public final class AhCommand implements CommandExecutor, TabCompleter {

    private final DonutAuctionsPlugin plugin;
    private final LanguageManager lang;
    private final AuctionService auctionService;
    private final AuctionMenuService menuService;
    private final EconomyHook economy;
    private final UltimateShopHook ultimateShop;

    public AhCommand(
            DonutAuctionsPlugin plugin,
            LanguageManager lang,
            AuctionService auctionService,
            AuctionMenuService menuService,
            EconomyHook economy,
            UltimateShopHook ultimateShop
    ) {
        this.plugin = plugin;
        this.lang = lang;
        this.auctionService = auctionService;
        this.menuService = menuService;
        this.economy = economy;
        this.ultimateShop = ultimateShop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(lang.text("messages.only-player"));
            return true;
        }

        if (!player.hasPermission("donutauctions.command.ah")) {
            player.sendMessage(lang.text("messages.no-permission"));
            return true;
        }

        if (args.length == 0) {
            menuService.openAuction(player, 1);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "sell" -> handleSell(player, args);
            case "my", "myitems" -> menuService.openMyItems(player, 1);
            case "transactions", "tx" -> menuService.openTransactions(player, 1);
            case "reload" -> {
                if (!player.hasPermission("donutauctions.admin.reload")) {
                    player.sendMessage(lang.text("messages.no-permission"));
                    return true;
                }
                plugin.reloadPluginState();
                player.sendMessage(lang.text("messages.reloaded"));
            }
            default -> player.sendMessage(lang.text("messages.unknown-subcommand"));
        }

        return true;
    }

    private void handleSell(Player player, String[] args) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            player.sendMessage(lang.text("messages.hold-item"));
            return;
        }

        if (mustBeUltimateSellable(player, hand) && !ultimateShop.isSellable(hand, player)) {
            player.sendMessage(lang.text("messages.not-sellable-in-ultimateshop"));
            return;
        }

        double price;
        if (shouldForceSuggestedPrice(player)) {
            OptionalDouble suggested = ultimateShop.suggestAuctionPrice(hand, player);
            if (suggested.isEmpty()) {
                player.sendMessage(lang.text("messages.price-required"));
                return;
            }
            price = suggested.getAsDouble();
            player.sendMessage(lang.text("messages.price-forced-suggested", Map.of("price", economy.format(price))));
        } else if (args.length >= 2) {
            try {
                price = Double.parseDouble(args[1]);
            } catch (NumberFormatException ex) {
                player.sendMessage(lang.text("messages.invalid-price"));
                return;
            }
        } else {
            OptionalDouble suggested = ultimateShop.suggestAuctionPrice(hand);
            if (suggested.isEmpty()) {
                player.sendMessage(lang.text("messages.price-required"));
                return;
            }
            price = suggested.getAsDouble();
            player.sendMessage(lang.text("messages.price-suggested", Map.of("price", economy.format(price))));
        }

        if (!Double.isFinite(price) || price <= 0) {
            player.sendMessage(lang.text("messages.invalid-price"));
            return;
        }
        double minPrice = plugin.getConfig().getDouble("auction.min-price", 0.01D);
        double maxPrice = plugin.getConfig().getDouble("auction.max-price", 1_000_000_000D);
        if (price < minPrice || price > maxPrice) {
            player.sendMessage(lang.text("messages.invalid-price-range"));
            return;
        }

        ItemStack toList = hand.clone();
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));

        Optional<AuctionListing> listing = auctionService.createListing(player, toList, price);
        if (listing.isEmpty()) {
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(toList);
            if (!overflow.isEmpty()) {
                overflow.values().forEach(stack -> player.getWorld().dropItemNaturally(player.getLocation(), stack));
            }
            player.sendMessage(lang.text("messages.max-listings-reached"));
            return;
        }

        player.sendMessage(lang.text("messages.listing-created", Map.of(
                "item", toList.getType().name(),
                "price", economy.format(price)
        )));
        menuService.openAuction(player, 1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return partial(args[0], List.of("sell", "my", "transactions", "reload"));
        }
        return List.of();
    }

    private List<String> partial(String input, List<String> options) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    private boolean shouldForceSuggestedPrice(Player player) {
        return ultimateShop.isActive()
                && plugin.getConfig().getBoolean("ultimateshop.force-recommended-price-when-enabled", false);
    }

    private boolean mustBeUltimateSellable(Player player, ItemStack item) {
        return ultimateShop.isActive()
                && plugin.getConfig().getBoolean("ultimateshop.only-sellable-items", false);
    }
}
