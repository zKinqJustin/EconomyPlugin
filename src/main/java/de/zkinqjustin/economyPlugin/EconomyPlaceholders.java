package de.zkinqjustin.economyPlugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class EconomyPlaceholders extends PlaceholderExpansion {
    private final EconomyPlugin plugin;

    public EconomyPlaceholders(EconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "economyplugin";
    }

    @Override
    public String getAuthor() {
        return "zKinqJustin";
    }

    @Override
    public String getVersion() {
        return "1.0";
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) {
            return "";
        }

        if (identifier.equals("balance")) {
            return String.format("%.2f", plugin.getBalance(player));
        }

        return null;
    }
}

