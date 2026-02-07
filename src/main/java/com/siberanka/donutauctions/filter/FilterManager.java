package com.siberanka.donutauctions.filter;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FilterManager {

    public record FilterRule(String id, List<Material> materials, List<String> contains) {
    }

    private final DonutAuctionsPlugin plugin;
    private final Map<String, FilterRule> rules = new HashMap<>();
    private final List<String> order = new ArrayList<>();

    public FilterManager(DonutAuctionsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        rules.clear();
        order.clear();

        File file = new File(plugin.getDataFolder(), "filters.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        List<String> configuredOrder = cfg.getStringList("order");
        if (!configuredOrder.isEmpty()) {
            configuredOrder.stream()
                    .map(id -> id.toLowerCase(Locale.ROOT))
                    .forEach(order::add);
        }

        ConfigurationSection filters = cfg.getConfigurationSection("filters");
        if (filters == null) {
            order.clear();
            order.add("all");
            rules.put("all", new FilterRule("all", List.of(), List.of()));
            return;
        }

        for (String key : filters.getKeys(false)) {
            String id = key.toLowerCase(Locale.ROOT);
            ConfigurationSection section = filters.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            List<Material> mats = new ArrayList<>();
            for (String raw : section.getStringList("materials")) {
                Material material = Material.matchMaterial(raw);
                if (material != null) {
                    mats.add(material);
                }
            }

            List<String> contains = section.getStringList("contains").stream()
                    .map(x -> x.toLowerCase(Locale.ROOT))
                    .toList();

            rules.put(id, new FilterRule(id, mats, contains));
            if (!order.contains(id)) {
                order.add(id);
            }
        }

        if (!rules.containsKey("all")) {
            rules.put("all", new FilterRule("all", List.of(), List.of()));
            order.remove("all");
            order.add(0, "all");
        }

        if (order.isEmpty()) {
            order.add("all");
        }
    }

    public List<String> order() {
        return Collections.unmodifiableList(order);
    }

    public String normalizeFilter(String input) {
        if (input == null) {
            return "all";
        }
        String id = input.toLowerCase(Locale.ROOT);
        return rules.containsKey(id) ? id : "all";
    }

    public String nextFilter(String current) {
        String active = normalizeFilter(current);
        int idx = order.indexOf(active);
        if (idx < 0) {
            return "all";
        }
        return order.get((idx + 1) % order.size());
    }

    public boolean matches(String filterId, ItemStack item) {
        String id = normalizeFilter(filterId);
        if (id.equals("all")) {
            return true;
        }

        FilterRule rule = rules.get(id);
        if (rule == null || item == null) {
            return false;
        }

        if (rule.materials().contains(item.getType())) {
            return true;
        }

        String materialName = item.getType().name().toLowerCase(Locale.ROOT);
        for (String keyword : rule.contains()) {
            if (materialName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
