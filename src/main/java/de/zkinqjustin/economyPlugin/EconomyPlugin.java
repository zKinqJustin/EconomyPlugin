package de.zkinqjustin.economyPlugin;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

public class EconomyPlugin extends JavaPlugin {
    private Economy economy;

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault not found! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register commands
        getCommand("balance").setExecutor(this);
        getCommand("pay").setExecutor(this);
        getCommand("balanceadd").setExecutor(this);

        // Register PlaceholderAPI expansion
        new EconomyPlaceholders(this).register();

        getLogger().info("EconomyPlugin has been enabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "balance":
                double balance = economy.getBalance(player);
                player.sendMessage("Your balance: $" + balance);
                return true;

            case "pay":
                if (args.length < 2) {
                    player.sendMessage("Usage: /pay <player> <amount>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    player.sendMessage("Player not found.");
                    return true;
                }
                try {
                    double amount = Double.parseDouble(args[1]);
                    if (amount <= 0) {
                        player.sendMessage("Amount must be positive.");
                        return true;
                    }
                    if (economy.has(player, amount)) {
                        economy.withdrawPlayer(player, amount);
                        economy.depositPlayer(target, amount);
                        player.sendMessage("You paid $" + amount + " to " + target.getName());
                        target.sendMessage("You received $" + amount + " from " + player.getName());
                    } else {
                        player.sendMessage("You don't have enough money.");
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage("Invalid amount.");
                }
                return true;

            case "balanceadd":
                if (!player.hasPermission("economyplugin.balanceadd")) {
                    player.sendMessage("You don't have permission to use this command.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("Usage: /balanceadd <player> <amount>");
                    return true;
                }
                Player targetPlayer = Bukkit.getPlayer(args[0]);
                if (targetPlayer == null) {
                    player.sendMessage("Player not found.");
                    return true;
                }
                try {
                    double amount = Double.parseDouble(args[1]);
                    economy.depositPlayer(targetPlayer, amount);
                    player.sendMessage("Added $" + amount + " to " + targetPlayer.getName() + "'s balance.");
                    targetPlayer.sendMessage("$" + amount + " has been added to your balance.");
                } catch (NumberFormatException e) {
                    player.sendMessage("Invalid amount.");
                }
                return true;
        }

        return false;
    }

    public Economy getEconomy() {
        return economy;
    }
}

