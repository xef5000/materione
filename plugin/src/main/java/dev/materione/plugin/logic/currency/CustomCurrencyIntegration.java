package dev.materione.plugin.logic.currency;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/**
 * The {@code custom} integration (logic.md §16): a currency a plugin of the server runs, driven by a
 * console command to give, a command to take, and a PlaceholderAPI token that reads the balance. It
 * is ported from ItemsAPI's custom integration.
 *
 * <p>Balances are read through PlaceholderAPI, which the integration reaches by reflection, so
 * Materione takes no compile dependency on it. Without PlaceholderAPI the currency is unavailable,
 * with that reason. Withdrawals check the balance first and run the take command only when it
 * covers the amount. A command that does not dispatch is {@code Failed}, never silently successful.
 */
public final class CustomCurrencyIntegration implements CurrencyIntegration {

    private static final String PLACEHOLDER_API = "PlaceholderAPI";
    private static volatile Method setPlaceholders;

    @Override
    public String id() {
        return "custom";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription("Custom currency",
                "A currency that a command gives and takes, with its balance read from a PlaceholderAPI token",
                List.of(
                        new ParameterSchema("deposit-command", ParameterType.STRING, true, null,
                                "Console command that gives money; {player} and {amount} are filled in", null, null),
                        new ParameterSchema("withdraw-command", ParameterType.STRING, true, null,
                                "Console command that takes money; {player} and {amount} are filled in", null, null),
                        new ParameterSchema("balance-placeholder", ParameterType.STRING, true, null,
                                "PlaceholderAPI token whose value is the balance as a number", null, null)),
                "2.0.0");
    }

    @Override
    public CurrencyBackend bind(ParamReader config) {
        return new CustomBackend(
                config.text("deposit-command"),
                config.text("withdraw-command"),
                config.text("balance-placeholder"));
    }

    private static final class CustomBackend implements CurrencyBackend {

        private final String depositCommand;
        private final String withdrawCommand;
        private final String placeholder;

        CustomBackend(String depositCommand, String withdrawCommand, String placeholder) {
            this.depositCommand = stripSlash(depositCommand);
            this.withdrawCommand = stripSlash(withdrawCommand);
            this.placeholder = placeholder;
        }

        @Override
        public Availability availability() {
            return Bukkit.getPluginManager().isPluginEnabled(PLACEHOLDER_API)
                    ? Availability.AVAILABLE
                    : Availability.unavailable("PlaceholderAPI is not installed (the custom currency reads its balance through it)");
        }

        @Override
        public EconomyResult balance(UUID player) {
            OfflinePlayer account = Bukkit.getOfflinePlayer(player);
            String text;
            try {
                text = placeholders(account, placeholder);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return new EconomyResult.Failed("PlaceholderAPI could not read the balance: " + e.getMessage());
            }
            try {
                return new EconomyResult.Succeeded(Double.parseDouble(text.trim()));
            } catch (NumberFormatException e) {
                return new EconomyResult.Failed("the balance placeholder returned a non-number: " + text);
            }
        }

        @Override
        public EconomyResult has(UUID player, double amount) {
            EconomyResult held = balance(player);
            if (held instanceof EconomyResult.Succeeded ok && ok.balance() < amount) {
                return new EconomyResult.InsufficientFunds(ok.balance());
            }
            return held;
        }

        @Override
        public EconomyResult withdraw(UUID player, double amount) {
            EconomyResult held = has(player, amount);
            if (!(held instanceof EconomyResult.Succeeded ok)) {
                return held;
            }
            return run(player, withdrawCommand, amount, ok.balance() - amount);
        }

        @Override
        public EconomyResult deposit(UUID player, double amount) {
            EconomyResult held = balance(player);
            if (!(held instanceof EconomyResult.Succeeded ok)) {
                return held;
            }
            return run(player, depositCommand, amount, ok.balance() + amount);
        }

        /** Runs {@code template} as the console; the reported balance is the expected one, the placeholder is authoritative. */
        private static EconomyResult run(UUID player, String template, double amount, double expected) {
            OfflinePlayer account = Bukkit.getOfflinePlayer(player);
            String name = account.getName();
            if (name == null) {
                return new EconomyResult.Failed("the player has no name to give the command");
            }
            String command = template.replace("{player}", name).replace("{amount}", amount(amount));
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                return new EconomyResult.Failed("the command did not run: " + command);
            }
            return new EconomyResult.Succeeded(expected);
        }

        private static String amount(double amount) {
            return amount == Math.rint(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
        }

        private static String stripSlash(String command) {
            return command.startsWith("/") ? command.substring(1) : command;
        }
    }

    /** Reads a PlaceholderAPI token for {@code player}, reflectively, so no compile dependency is needed. */
    static String placeholders(OfflinePlayer player, String text) throws ReflectiveOperationException {
        Method method = setPlaceholders;
        if (method == null) {
            Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            method = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            setPlaceholders = method;
        }
        return (String) method.invoke(null, player, text);
    }
}
