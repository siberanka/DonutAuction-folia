package com.siberanka.donutauctions.config;

import com.siberanka.donutauctions.DonutAuctionsPlugin;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LanguageManager {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    private final DonutAuctionsPlugin plugin;
    private YamlConfiguration active;

    public LanguageManager(DonutAuctionsPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        String language = plugin.getConfig().getString("language", "en").toLowerCase();

        File folder = new File(plugin.getDataFolder(), "lang");
        if (!folder.exists()) {
            folder.mkdirs();
        }

        File file = new File(folder, language + ".yml");
        if (!file.exists()) {
            file = new File(folder, "en.yml");
        }
        active = YamlConfiguration.loadConfiguration(file);
    }

    public String text(String path) {
        String raw = active.getString(path, path);
        return applyPlaceholders(colorize(raw), defaultPlaceholders());
    }

    public String text(String path, Map<String, String> placeholders) {
        return applyPlaceholders(text(path), placeholders);
    }

    public List<String> textList(String path) {
        List<String> raw = active.getStringList(path);
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        return raw.stream().map(this::colorize).toList();
    }

    public List<String> textList(String path, Map<String, String> placeholders) {
        return textList(path).stream().map(line -> applyPlaceholders(line, placeholders)).toList();
    }

    public int number(String path, int def) {
        return active.getInt(path, def);
    }

    public String rawString(String path, String def) {
        return active.getString(path, def);
    }

    public YamlConfiguration config() {
        return active;
    }

    private String applyPlaceholders(String input, Map<String, String> placeholders) {
        Map<String, String> merged = new HashMap<>(defaultPlaceholders());
        if (placeholders != null) {
            merged.putAll(placeholders);
        }

        Matcher matcher = PLACEHOLDER.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = merged.getOrDefault(matcher.group(1), matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Map<String, String> defaultPlaceholders() {
        return Map.of("prefix", colorize(active.getString("prefix", "")));
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}