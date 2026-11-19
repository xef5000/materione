package dev.materione.integrations.coinsengine;

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
import java.util.Set;
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
 * The CoinsEngine adapter (logic.md §18), driven through the same reflection it uses against the real
 * plugin: a service registered under the real interface's name, four methods found by name and shape. The
 * test doubles in {@code su.nightexpress.excellenteconomy.api} stand in for CoinsEngine, whose API cannot
 * be compiled against.
 */
class CoinsEngineCurrencyTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    /** A CoinsEngine with two currencies and a balance per (currency, player), as the plugin would keep them. */
    private final Map<String, ExcellentCurrency> currencies = new HashMap<>();
    private final Map<String, Double> balances = new HashMap<>();
    private final Player alice = player(ALICE);
    private ExcellentEconomyAPI service;
    private boolean registered;

    @BeforeEach
    void install() throws Exception {
        for (String id : Set.of("coins", "gems")) {
            currencies.put(id, () -> id);
        }
        service = (ExcellentEconomyAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ExcellentEconomyAPI.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCurrency" -> currencies.get((String) args[0]);
                    case "getBalance" -> balance((Player) args[0], (ExcellentCurrency) args[1]);
                    case "deposit" -> {
                        balances.merge(key((Player) args[0], (ExcellentCurrency) args[1]), (Double) args[2], Double::sum);
                        yield true;
                    }
                    case "withdraw" -> {
                        balances.merge(key((Player) args[0], (ExcellentCurrency) args[1]), -(Double) args[2], Double::sum);
                        yield true;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> "service";
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
        var integration = new CoinsEngineCurrencyIntegration();

        assertThatThrownBy(() -> integration.bind(ParamReader.of(Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> integration.bind(ParamReader.of(Map.of("currency", "  "))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(integration.id()).isEqualTo("coinsengine");
        assertThat(integration.describe().parameters()).extracting(p -> p.id()).containsExactly("currency");
    }

    @Test
    void oneCoinsEngineCurrencyIsOneMaterioneCurrencyAndSeveralCanShareThePlugin() {
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

        assertThat(missing.availability()).isEqualTo(Availability.unavailable("CoinsEngine has no currency named rubies"));
        assertThat(missing.balance(ALICE)).isInstanceOf(EconomyResult.Failed.class);
    }

    @Test
    void aPlayerWhoIsOfflineIsRefusedWithAReason() {
        CurrencyBackend coins = backend("coins");

        assertThat(coins.balance(BOB)).isEqualTo(new EconomyResult.Failed("CoinsEngine currencies need the player online"));
        assertThat(coins.availability()).isEqualTo(Availability.AVAILABLE);
    }

    @Test
    void withoutCoinsEngineRunningTheCurrencyIsUnavailable() {
        registered = false;
        CurrencyBackend coins = backend("coins");

        assertThat(coins.availability()).isEqualTo(Availability.unavailable("CoinsEngine is not running"));
        assertThat(coins.readBindingIdentity()).isNull();
    }

    private CurrencyBackend backend(String currency) {
        return new CoinsEngineCurrencyIntegration().bind(ParamReader.of(Map.of("currency", currency)));
    }

    private double balance(Player player, ExcellentCurrency currency) {
        return balances.getOrDefault(key(player, currency), 0d);
    }

    private static String key(Player player, ExcellentCurrency currency) {
        return currency.getId() + ":" + player.getUniqueId();
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player " + id;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private Server server() {
        ServicesManager services = (ServicesManager) Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                new Class<?>[] {ServicesManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getKnownServices" -> registered ? Set.of(ExcellentEconomyAPI.class) : Set.of();
                    case "load" -> registered ? service : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getServicesManager" -> services;
                    case "getPlayer" -> ALICE.equals(args[0]) ? alice : null;
                    case "getLogger" -> Logger.getLogger("test");
                    case "getName" -> "Test";
                    case "getVersion", "getBukkitVersion" -> "test";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Installs or clears Bukkit's static server, which {@code Bukkit.setServer} refuses to replace. */
    private static void setServer(Server server) throws Exception {
        Field field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, server);
    }
}
