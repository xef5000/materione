package dev.materione.plugin.logic.currency;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.LogicRegistration;
import dev.materione.api.logic.LogicRegistry;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.logic.provider.CurrencyIntegration;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariablesApi;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.integrations.variables.VariablePlaceholders;
import dev.materione.plugin.logic.GatewayRunScheduler.ThreadProbe;
import dev.materione.plugin.logic.variable.CurrencyPlaceholderReads;
import dev.materione.plugin.logic.variable.MaterionePlaceholders;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.clip.placeholderapi.replacer.CharsReplacer;
import me.clip.placeholderapi.replacer.Replacer;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class CustomCurrencyPlaceholderTest {
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private static final NamespacedId ID = NamespacedId.of("shop", "gold");
    private static final Map<String, Object> CONFIG = Map.of(
            "deposit-command", "give {player} {amount}", "withdraw-command", "take {player} {amount}",
            "balance-placeholder", "%example_balance%");

    private static final class Papi implements CustomCurrencyIntegration.PapiAccess {
        Object plugin = new Object();
        boolean enabled = true;
        double value = 12.5;
        Runnable callback = () -> { };
        int calls;
        final Map<String, PlaceholderExpansion> registered = new HashMap<>();
        final PlaceholderExpansion expansion = new PlaceholderExpansion() {
            @Override public String getIdentifier() { return "example"; }
            @Override public String getAuthor() { return "test"; }
            @Override public String getVersion() { return "1"; }
            @Override public String onRequest(OfflinePlayer player, String params) {
                assertThat(player.getUniqueId()).isEqualTo(VIEWER);
                assertThat(params).isEqualTo("balance");
                calls++;
                callback.run();
                return Double.toString(value);
            }
        };
        Papi() { registered.put("example", expansion); }
        @Override public Object current() { return enabled ? plugin : null; }
        @Override public Map<String, ?> expansions(Object binding) { return registered; }
        @Override public String replace(Object binding, OfflinePlayer player, String text) {
            return new CharsReplacer(Replacer.Closure.PERCENT).apply(text, player, registered::get);
        }
    }

    private static final class Fixture {
        final Papi papi = new Papi();
        final Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> VIEWER;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError(method.getName());
                });
        final CurrencyIntegration custom = new CustomCurrencyIntegration(papi, id -> player);
        final LogicRegistry<CurrencyIntegration> registry = new LogicRegistry<>() {
            @Override public LogicRegistration register(Plugin owner, NamespacedId id, CurrencyIntegration value) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<CurrencyIntegration> get(NamespacedId id) {
                return id.equals(NamespacedId.of("materione", "custom")) ? Optional.of(custom) : Optional.empty();
            }
            @Override public Optional<CurrencyIntegration> resolve(String name) {
                return name.equals("custom") || name.equals("materione:custom")
                        ? Optional.of(custom) : Optional.empty();
            }
            @Override public List<RegistryEntry<CurrencyIntegration>> entries() {
                return List.of(new RegistryEntry<>(NamespacedId.of("materione", "custom"), custom,
                        custom.describe(), "test", 1));
            }
        };
        final MaterioneCurrencyCatalog catalog = new MaterioneCurrencyCatalog(registry::resolve, null);
        final ThreadProbe probe = new ThreadProbe() {
            @Override public boolean onGlobalThread() { return true; }
            @Override public boolean ownsEntity(Entity entity) { return true; }
        };
        final List<dev.materione.api.diagnostics.Diagnostic> diagnostics = new ArrayList<>();
        final MaterionePlaceholders expansion;

        Fixture() throws ReflectiveOperationException {
            define("$", 2);
            LogicProviders providers = (LogicProviders) Proxy.newProxyInstance(LogicProviders.class.getClassLoader(),
                    new Class<?>[] {LogicProviders.class}, (proxy, method, args) -> {
                        if (method.getName().equals("currencies")) return catalog;
                        throw new AssertionError(method.getName());
                    });
            VariablesApi variables = (VariablesApi) Proxy.newProxyInstance(VariablesApi.class.getClassLoader(),
                    new Class<?>[] {VariablesApi.class}, (proxy, method, args) -> {
                        throw new AssertionError(method.getName());
                    });
            var bridge = new CurrencyPlaceholderReads(providers, registry, new CurrencyReads(false, probe),
                    probe, id -> player, false);
            var resolver = new VariablePlaceholders(variables, () -> GuiSessionPort.NONE, bridge, diagnostics::add);
            var constructor = MaterionePlaceholders.class.getDeclaredConstructor(VariablePlaceholders.class, String.class);
            constructor.setAccessible(true);
            expansion = constructor.newInstance(resolver, "1");
        }
        void define(String symbol, int decimals) {
            catalog.define(List.of(new Currency(ID, "Gold", symbol, decimals, "custom", CONFIG)));
        }
        String raw() { return expansion.onRequest(player, "balance_shop_gold"); }
        String formatted() { return expansion.onRequest(player, "balance_shop_gold_formatted"); }
    }

    @Test
    void actualExpansionCallbackReadsFreshValuesAndLiveDefinition() throws Exception {
        var fixture = new Fixture();
        assertThat(fixture.raw()).as("diagnostics: %s", fixture.diagnostics).isEqualTo("12.5");
        fixture.papi.value = 1234;
        assertThat(fixture.formatted()).isEqualTo("$1,234.00");
        fixture.define("G", 0);
        assertThat(fixture.formatted()).isEqualTo("G1,234");
        assertThat(fixture.papi.calls).isEqualTo(3);
    }

    @Test
    void replacementDuringCallbackIsUnavailableRatherThanMixed() throws Exception {
        var fixture = new Fixture();
        fixture.papi.callback = () -> fixture.define("NEW", 0);
        assertThat(fixture.formatted()).isEmpty();
        fixture.papi.callback = () -> fixture.papi.registered.clear();
        assertThat(fixture.raw()).isEmpty();
        fixture.papi.registered.put("example", fixture.papi.expansion);
        var replacement = new PlaceholderExpansion() {
            @Override public String getIdentifier() { return "example"; }
            @Override public String getAuthor() { return "test"; }
            @Override public String getVersion() { return "1"; }
        };
        assertThat(replacement.equals(fixture.papi.expansion)).isTrue();
        fixture.papi.callback = () -> fixture.papi.registered.put("example", replacement);
        assertThat(fixture.raw()).isEmpty();
        fixture.papi.registered.put("example", fixture.papi.expansion);
        fixture.papi.callback = () -> fixture.papi.plugin = new Object();
        assertThat(fixture.raw()).isEmpty();
        fixture.papi.callback = () -> { };
        assertThat(fixture.formatted()).isEqualTo("NEW13");
    }

    @Test
    void disabledAndReenabledPapiAndExpansionAreResolvedAtUse() throws Exception {
        var fixture = new Fixture();
        assertThat(fixture.raw()).isEqualTo("12.5");
        fixture.papi.enabled = false;
        assertThat(fixture.raw()).isEmpty();
        fixture.papi.enabled = true;
        fixture.papi.plugin = new Object();
        fixture.papi.value = 7;
        assertThat(fixture.raw()).isEqualTo("7");
        fixture.papi.registered.clear();
        assertThat(fixture.raw()).isEmpty();
        fixture.papi.registered.put("example", fixture.papi.expansion);
        assertThat(fixture.raw()).isEqualTo("7");
        assertThat(fixture.diagnostics).isNotEmpty();
    }
}
