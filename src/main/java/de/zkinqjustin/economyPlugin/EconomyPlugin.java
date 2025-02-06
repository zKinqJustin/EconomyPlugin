package de.zkinqjustin.economyPlugin;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EconomyPlugin extends JavaPlugin implements Economy {
    private Connection connection;
    private String host, database, username, password;
    private int port;
    private String currencyNameSingular, currencyNamePlural;

    @Override
    public void onEnable() {
        // Load configuration
        saveDefaultConfig();
        host = getConfig().getString("database.host", "localhost");
        port = getConfig().getInt("database.port", 3306);
        database = getConfig().getString("database.name", "economy");
        username = getConfig().getString("database.username", "root");
        password = getConfig().getString("database.password", "");

        currencyNameSingular = getConfig().getString("currency.singular", "dollar");
        currencyNamePlural = getConfig().getString("currency.plural", "dollars");

        // Initialize database connection
        if (!initializeDatabase()) {
            getLogger().severe("Failed to initialize database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register this plugin as the Economy provider for Vault
        getServer().getServicesManager().register(Economy.class, this, this, ServicePriority.Normal);

        // Register commands
        Objects.requireNonNull(getCommand("balance")).setExecutor(this);
        Objects.requireNonNull(getCommand("pay")).setExecutor(this);
        Objects.requireNonNull(getCommand("balanceadd")).setExecutor(this);
        Objects.requireNonNull(getCommand("bank")).setExecutor(this);

        // Register PlaceholderAPI expansion
        new EconomyPlaceholders(this).register();

        // Tab Completer registrieren
        Objects.requireNonNull(getCommand("balance")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("pay")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("balanceadd")).setTabCompleter(this);
        Objects.requireNonNull(getCommand("bank")).setTabCompleter(this);

        getLogger().info("EconomyPlugin has been enabled!");
    }

    @Override
    public void onDisable() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                getLogger().severe("Error closing database connection: " + e.getMessage());
            }
        }
    }

    private boolean initializeDatabase() {
        try {
            Class.forName("org.mariadb.jdbc.Driver");
            connection = DriverManager.getConnection("jdbc:mariadb://" + host + ":" + port + "/" + database, username, password);

            // Create tables if not exists
            try (PreparedStatement stmt = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS player_balances (uuid VARCHAR(36) PRIMARY KEY, balance DOUBLE NOT NULL)")) {
                stmt.executeUpdate();
            }
            
            try (PreparedStatement stmt = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS bank_balances (uuid VARCHAR(36) PRIMARY KEY, balance DOUBLE NOT NULL)")) {
                stmt.executeUpdate();
            }

            return true;
        } catch (ClassNotFoundException | SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(colorize(getConfig().getString("messages.player-only", "&cThis command can only be used by players.")));
            return true;
        }

        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "balance":
                double balance = getBalance(player);
                player.sendMessage(colorize(getConfig().getString("messages.balance", "&aYour balance: %amount%"))
                        .replace("%amount%", format(balance)));
                return true;

            case "pay":
                if (args.length < 2) {
                    player.sendMessage(colorize(getConfig().getString("messages.pay-usage", "&cUsage: /pay <player> <amount>")));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[0]);
                if (target == null) {
                    player.sendMessage(colorize(getConfig().getString("messages.player-not-found", "&cPlayer not found.")));
                    return true;
                }
                try {
                    double amount = Double.parseDouble(args[1]);
                    if (amount <= 0) {
                        player.sendMessage(colorize(getConfig().getString("messages.invalid-amount", "&cAmount must be positive.")));
                        return true;
                    }
                    EconomyResponse response = withdrawPlayer(player, amount);
                    if (response.transactionSuccess()) {
                        depositPlayer(target, amount);
                        player.sendMessage(colorize(getConfig().getString("messages.pay-success", "&aYou paid %amount% to %player%"))
                                .replace("%amount%", format(amount))
                                .replace("%player%", target.getName()));
                        target.sendMessage(colorize(getConfig().getString("messages.pay-received", "&aYou received %amount% from %player%"))
                                .replace("%amount%", format(amount))
                                .replace("%player%", player.getName()));
                    } else {
                        player.sendMessage(colorize(getConfig().getString("messages.insufficient-funds", "&cYou don't have enough money.")));
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(colorize(getConfig().getString("messages.invalid-amount", "&cInvalid amount.")));
                }
                return true;

            case "balanceadd":
                if (!player.hasPermission("economyplugin.balanceadd")) {
                    player.sendMessage(colorize(getConfig().getString("messages.no-permission", "&cYou don't have permission to use this command.")));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(colorize(getConfig().getString("messages.balanceadd-usage", "&cUsage: /balanceadd <player> <amount>")));
                    return true;
                }
                OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(args[0]);
                try {
                    double amount = Double.parseDouble(args[1]);
                    EconomyResponse response = depositPlayer(targetPlayer, amount);
                    if (response.transactionSuccess()) {
                        player.sendMessage(colorize(getConfig().getString("messages.balanceadd-success", "&aAdded %amount% to %player%'s balance."))
                                .replace("%amount%", format(amount))
                                .replace("%player%", targetPlayer.getName()));
                        if (targetPlayer.isOnline()) {
                            ((Player) targetPlayer).sendMessage(colorize(getConfig().getString("messages.balanceadd-received", "&a%amount% has been added to your balance."))
                                    .replace("%amount%", format(amount)));
                        }
                    } else {
                        player.sendMessage(colorize(getConfig().getString("messages.balanceadd-failed", "&cFailed to add money: %error%"))
                                .replace("%error%", response.errorMessage));
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(colorize(getConfig().getString("messages.invalid-amount", "&cInvalid amount.")));
                }
                return true;

            case "bank":
                if (args.length == 0) {
                    // Zeige Bankkontostand
                    double bankBalance = getBankBalance(player);
                    player.sendMessage(colorize("&aIhr Bankkontostand: " + format(bankBalance)));
                    return true;
                }
                
                if (args.length != 2) {
                    player.sendMessage(colorize("&cVerwendung: /bank [deposit/withdraw] <Betrag>"));
                    return true;
                }
                
                try {
                    double amount = Double.parseDouble(args[1]);
                    if (amount <= 0) {
                        player.sendMessage(colorize("&cDer Betrag muss positiv sein."));
                        return true;
                    }
                    
                    switch (args[0].toLowerCase()) {
                        case "deposit":
                            if (!has(player, amount)) {
                                player.sendMessage(colorize("&cSie haben nicht genügend Geld."));
                                return true;
                            }
                            withdrawPlayer(player, amount);
                            depositToBank(player, amount);
                            player.sendMessage(colorize("&a" + format(amount) + " wurden auf Ihr Bankkonto eingezahlt."));
                            break;
                            
                        case "withdraw":
                            if (!hasBankBalance(player, amount)) {
                                player.sendMessage(colorize("&cNicht genügend Geld auf dem Bankkonto."));
                                return true;
                            }
                            withdrawFromBank(player, amount);
                            depositPlayer(player, amount);
                            player.sendMessage(colorize("&a" + format(amount) + " wurden von Ihrem Bankkonto abgehoben."));
                            break;
                            
                        default:
                            player.sendMessage(colorize("&cVerwendung: /bank [deposit/withdraw] <Betrag>"));
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(colorize("&cUngültiger Betrag."));
                }
                return true;
        }

        return false;
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    // Implement Economy methods

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return String.format("%.2f %s", amount, amount == 1 ? currencyNameSingular : currencyNamePlural);
    }

    @Override
    public String currencyNamePlural() {
        return currencyNamePlural;
    }

    @Override
    public String currencyNameSingular() {
        return currencyNameSingular;
    }

    @Override
    public boolean hasAccount(String playerName) {
        return hasAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT 1 FROM player_balances WHERE uuid = ?")) {
            stmt.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT balance FROM player_balances WHERE uuid = ?")) {
            stmt.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                } else {
                    // If player doesn't exist, create account with 0 balance
                    createPlayerAccount(player);
                    return 0.0;
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return 0.0;
        }
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Cannot withdraw negative funds");
        }

        if (!has(player, amount)) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }

        try (PreparedStatement stmt = connection.prepareStatement("UPDATE player_balances SET balance = balance - ? WHERE uuid = ?")) {
            stmt.setDouble(1, amount);
            stmt.setString(2, player.getUniqueId().toString());
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
            } else {
                return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Player not found");
            }
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Database error");
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Cannot deposit negative funds");
        }

        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO player_balances (uuid, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = balance + ?")) {
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setDouble(2, amount);
            stmt.setDouble(3, amount);
            int updated = stmt.executeUpdate();
            if (updated > 0) {
                return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
            } else {
                return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Player not found");
            }
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "Database error");
        }
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (hasAccount(player)) {
            return false;
        }

        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO player_balances (uuid, balance) VALUES (?, 0)")) {
            stmt.setString(1, player.getUniqueId().toString());
            int updated = stmt.executeUpdate();
            return updated > 0;
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return false;
        }
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    public Connection getConnection() {
        return connection;
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank accounts are not supported");
    }

    @Override
    public List<String> getBanks() {
        return new ArrayList<>();
    }

    // Neue Bank-Methoden
    private double getBankBalance(OfflinePlayer player) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT balance FROM bank_balances WHERE uuid = ?")) {
            stmt.setString(1, player.getUniqueId().toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("balance");
                } else {
                    createBankAccount(player);
                    return 0.0;
                }
            }
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
            return 0.0;
        }
    }

    private boolean hasBankBalance(OfflinePlayer player, double amount) {
        return getBankBalance(player) >= amount;
    }

    private void createBankAccount(OfflinePlayer player) {
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO bank_balances (uuid, balance) VALUES (?, 0)")) {
            stmt.setString(1, player.getUniqueId().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
        }
    }

    private void depositToBank(OfflinePlayer player, double amount) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO bank_balances (uuid, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = balance + ?")) {
            stmt.setString(1, player.getUniqueId().toString());
            stmt.setDouble(2, amount);
            stmt.setDouble(3, amount);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
        }
    }

    private void withdrawFromBank(OfflinePlayer player, double amount) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "UPDATE bank_balances SET balance = balance - ? WHERE uuid = ?")) {
            stmt.setDouble(1, amount);
            stmt.setString(2, player.getUniqueId().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Database error: " + e.getMessage());
        }
    }

    // Tab Completion für Bank-Befehle
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        
        if (command.getName().equalsIgnoreCase("bank")) {
            if (args.length == 1) {
                completions.add("deposit");
                completions.add("withdraw");
                return completions;
            }
        }
        
        return null;
    }
}

