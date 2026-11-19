package dev.materione.integrations.excellenteconomy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.logic.Availability;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.provider.CurrencyBackend;
import dev.materione.api.logic.provider.EconomyResult;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import su.nightexpress.excellenteconomy.api.ExcellentEconomyAPI;
import su.nightexpress.excellenteconomy.api.currency.ExcellentCurrency;

/**
 * The ExcellentEconomy adapter (logic.md §18), compiled and run against the plugin's real API types: the
 * service interface and the currency are the published ones, and only their behaviour is stood in for by
 * proxies (no ExcellentEconomy server is started). So a method the adapter calls that the API no longer has
 * fails this module's build, not a player's purchase.
 */
class ExcellentEconomyCurrencyTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    /** An ExcellentEconomy with two currencies and a balance per (currency, player), as the plugin keeps them. */
    private final Map<String, ExcellentCurrency> currencies = new HashMap<>();
    private final Map<String, Double> balances = new HashMap<>();
    private final Player alice = player(ALICE);
    private ExcellentEconomyAPI service;
    private boolean registered;

    @BeforeEach
    void install() throws Exception {
        for (String id : new String[] {"coins", "gems"}) {
            currencies.put(id, currency(id));
        }
        service = (ExcellentEconomyAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ExcellentEconomyAPI.class}, (proxy, method, args) -> {
                    boolean player = args != null && args.length > 1 && args[0] instanceof Player
                            && args[1] instanceof ExcellentCurrency;
                    return switch (method.getName()) {
                        case "getCurrency" -> currencies.get((String) args[0]);
                        case "getBalance" -> player ? balance((Player) args[0], (ExcellentCurrency) args[1]) : fail(method);
                        case "deposit" -> player ? change((Player) args[0], (ExcellentCurrency) args[1], (Double) args[2]) : fail(method);
                        case "withdraw" -> player ? change((Player) args[0], (ExcellentCurrency) args[1], -(Double) args[2]) : fail(method);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> fail(method);
                    };
                });
        registered = true;
        setServer(server());
    }

    @AfterEach
    void uninstall() throws Exception {
        setServer(null);
    }

    @Test
    void aCurrencyFileNeedsTheCurrencyKey() {
        var integration = new ExcellentEconomyCurrencyIntegration();

        assertThatThrownBy(() -> integration.bind(ParamReader.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> integration.bind(ParamReader.of(Map.of("currency", "  "))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(integration.id()).isEqualTo("excellenteconomy");
        assertThat(integration.describe().parameters()).extracting(p -> p.id()).containsExactly("currency");
    }

    @Test
    void oneExcellentEconomyCurrencyIsOneMaterioneCurrencyAndSeveralCanShareThePlugin() {
        CurrencyBackend coins = backend("coins");
        CurrencyBackend gems = backend("gems");
        coins.deposit(ALICE, 100);
        gems.deposit(ALICE, 7);

        assertThat(coins.balance(ALICE)).isEqualTo(new EconomyResult.Succeeded(100));
        assertThat(gems.balance(ALICE)).isEqualTo(new EconomyResult.Succeeded(7));
    }

    @Test
    void aWithdrawalTheBalanceCannotCoverTakesNothing() {
        CurrencyBackend coins = backend("coins");
        coins.deposit(ALICE, 10);

        assertThat(coins.withdraw(ALICE, 25)).isEqualTo(new EconomyResult.InsufficientFunds(10));
        assertThat(coins.balance(ALICE)).isEqualTo(new EconomyResult.Succeeded(10));
        assertThat(coins.withdraw(ALICE, 4)).isEqualTo(new EconomyResult.Succeeded(6));
        assertThat(coins.has(ALICE, 6)).isEqualTo(new EconomyResult.Succeeded(6));
        assertThat(coins.has(ALICE, 7)).isEqualTo(new EconomyResult.InsufficientFunds(6));
    }

    @Test
    void anUnknownCurrencyIsUnavailableWithTheReasonNotAnError() {
        CurrencyBackend missing = backend("rubies");

        assertThat(missing.availability())
                .isEqualTo(Availability.unavailable("ExcellentEconomy has no currency named rubies"));
        assertThat(missing.balance(ALICE)).isInstanceOf(EconomyResult.Failed.class);
    }

    @Test
    void aPlayerWhoIsOfflineIsRefusedWithAReason() {
        CurrencyBackend coins = backend("coins");

        assertThat(coins.balance(BOB))
                .isEqualTo(new EconomyResult.Failed("ExcellentEconomy currencies need the player online"));
        assertThat(coins.availability()).isEqualTo(Availability.AVAILABLE);
    }

    @Test
    void withoutExcellentEconomyRunningTheCurrencyIsUnavailable() {
        registered = false;
        CurrencyBackend coins = backend("coins");

        assertThat(coins.availability()).isEqualTo(Availability.unavailable("ExcellentEconomy is not running"));
    }

    private CurrencyBackend backend(String currency) {
        return new ExcellentEconomyCurrencyIntegration().bind(ParamReader.of(Map.of("currency", currency)));
    }

    private double balance(Player player, ExcellentCurrency currency) {
        return balances.getOrDefault(key(player, currency), 0d);
    }

    private boolean change(Player player, ExcellentCurrency currency, double delta) {
        balances.merge(key(player, currency), delta, Double::sum);
        return true;
    }

    private static Object fail(java.lang.reflect.Method method) {
        throw new UnsupportedOperationException(method.getName());
    }

    private static String key(Player player, ExcellentCurrency currency) {
        return currency.getId() + ":" + player.getUniqueId();
    }

    private static ExcellentCurrency currency(String id) {
        return (ExcellentCurrency) Proxy.newProxyInstance(ExcellentCurrency.class.getClassLoader(),
                new Class<?>[] {ExcellentCurrency.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "currency " + id;
                    default -> fail(method);
                });
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player " + id;
                    default -> fail(method);
                });
    }

    private Server server() {
        ServicesManager services = (ServicesManager) Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                new Class<?>[] {ServicesManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "load" -> registered && args[0] == ExcellentEconomyAPI.class ? service : null;
                    default -> fail(method);
                });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServicesManager" -> services;
                    case "getPlayer" -> ALICE.equals(args[0]) ? alice : null;
                    case "getLogger" -> Logger.getLogger("test");
                    case "getName" -> "Test";
                    case "getVersion", "getBukkitVersion" -> "test";
                    default -> fail(method);
                });
    }

    /** Installs or clears Bukkit's static server, which {@code Bukkit.setServer} refuses to replace. */
    private static void setServer(Server server) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }
}
