package dev.materione.integrations.variables;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.provider.EconomyResult;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.logic.variable.VariablesApi;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class VariablePlaceholdersTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** A declarations-and-values map standing in for the api. */
    private static final class FakeApi implements VariablesApi {
        final List<VariableDeclaration> declared;
        final Map<String, Object> values = new HashMap<>();

        FakeApi(List<VariableDeclaration> declared) {
            this.declared = declared;
        }

        @Override
        public Collection<VariableDeclaration> declarations() {
            return declared;
        }

        @Override
        public Optional<VariableDeclaration> declaration(NamespacedId id) {
            return declared.stream().filter(d -> d.id().equals(id)).findFirst();
        }

        @Override
        public Optional<Object> get(NamespacedId id, UUID player) {
            return declaration(id).map(d -> values.getOrDefault(
                    d.scope() == VariableScope.PLAYER ? player + "/" + id : "server/" + id, d.defaultValue()));
        }

        @Override
        public ApiResult<Object> set(ActorContext actor, NamespacedId id, UUID player, Object value) {
            throw new UnsupportedOperationException("not used by placeholders");
        }
    }

    private static final VariableDeclaration COINS = new VariableDeclaration(
            NamespacedId.of("shop", "coins"), VariableScope.PLAYER, VariableType.NUMBER, 0, null);
    private static final VariableDeclaration SEASON = new VariableDeclaration(
            NamespacedId.of("shop", "season"), VariableScope.SERVER, VariableType.STRING, "spring", null);
    private static final VariableDeclaration CLAIMED = new VariableDeclaration(
            NamespacedId.of("shop", "claimed"), VariableScope.PLAYER, VariableType.BOOLEAN, false, null);
    private static final VariableDeclaration COINS_LONG = new VariableDeclaration(
            NamespacedId.of("shop", "coins_total"), VariableScope.PLAYER, VariableType.NUMBER, 0, null);

    private final FakeApi api = new FakeApi(List.of(COINS, SEASON, CLAIMED, COINS_LONG));
    private final Map<String, UUID> players = Map.of("Steve", STEVE, "Alex", ALEX);
    private final VariablePlaceholders placeholders = new VariablePlaceholders(api, () -> GuiSessionPort.NONE);

    private String resolve(String identifier, UUID viewer) {
        return placeholders.resolve(identifier, viewer, name -> Optional.ofNullable(players.get(name)));
    }

    @Test
    void aPlayerVariableExpandsForTheViewer() {
        api.values.put(STEVE + "/shop:coins", 12.0);

        assertThat(resolve("var_shop_coins", STEVE)).isEqualTo("12");
        assertThat(resolve("var_shop_coins", ALEX)).isEqualTo("0");
    }

    @Test
    void aNamedPlayerOverridesTheViewer() {
        api.values.put(ALEX + "/shop:coins", 3.0);

        assertThat(resolve("var_shop_coins_Alex", STEVE)).isEqualTo("3");
    }

    @Test
    void aNamedPlayerNobodyKnowsResolvesToNull() {
        assertThat(resolve("var_shop_coins_Nobody", STEVE)).isNull();
    }

    @Test
    void aServerVariableIgnoresThePlayer() {
        api.values.put("server/shop:season", "winter");

        assertThat(resolve("var_shop_season", null)).isEqualTo("winter");
        assertThat(resolve("var_shop_season", STEVE)).isEqualTo("winter");
    }

    @Test
    void booleansAndUndeclaredNamesRenderAsDeclaredOrNull() {
        api.values.put(STEVE + "/shop:claimed", true);

        assertThat(resolve("var_shop_claimed", STEVE)).isEqualTo("true");
        assertThat(resolve("var_shop_gems", STEVE)).isNull();
        assertThat(resolve("var_nothing", STEVE)).isNull();
    }

    @Test
    void theLongestDeclaredPrefixWinsWhenNamesShareAStem() {
        api.values.put(STEVE + "/shop:coins_total", 99.0);

        assertThat(resolve("var_shop_coins_total", STEVE)).isEqualTo("99");
        assertThat(resolve("var_shop_coins_Alex", STEVE)).isEqualTo("0");
    }

    @Test
    void aPlayerVariableWithNoViewerIsEmpty() {
        assertThat(resolve("var_shop_coins", null)).isEmpty();
    }

    @Test
    void theGuiPlaceholdersReadThePortAndAreEmptyWithoutIt() {
        assertThat(resolve("gui_open", STEVE)).isEmpty();
        assertThat(resolve("gui_last", STEVE)).isEmpty();

        var withPort = new VariablePlaceholders(api, () -> new GuiSessionPort() {
            @Override
            public Optional<String> openGui(UUID player) {
                return Optional.of("shop_menu");
            }

            @Override
            public Optional<String> lastGui(UUID player) {
                return Optional.of("home");
            }
        });
        assertThat(withPort.resolve("gui_open", STEVE, name -> Optional.empty())).isEqualTo("shop_menu");
        assertThat(withPort.resolve("gui_last", STEVE, name -> Optional.empty())).isEqualTo("home");
    }

    @Test
    void withoutABridgeBalanceTokensRemainUnresolved() {
        assertThat(resolve("balance_shop_gold", STEVE)).isNull();
        assertThat(resolve("balance_shop_gold_formatted", STEVE)).isNull();
    }

    private static final class BalanceBridge implements CurrencyPlaceholderBridge {
        List<NamespacedId> ids = new ArrayList<>();
        EconomyResult result = new EconomyResult.Succeeded(0);
        NamespacedId readId;
        UUID readViewer;
        NamespacedId formattedId;
        int reads;
        boolean throwsOnRead;
        boolean throwsOnCatalog;
        boolean throwsOnFormat;
        String formatted = "formatted";

        @Override
        public Collection<NamespacedId> currencies() {
            if (throwsOnCatalog) {
                throw new IllegalStateException("catalog unavailable");
            }
            return ids;
        }

        @Override
        public Balance read(NamespacedId currency, UUID viewer, boolean wantsFormatting) {
            reads++;
            readId = currency;
            readViewer = viewer;
            if (throwsOnRead) {
                throw new IllegalStateException("provider removed");
            }
            if (wantsFormatting) {
                formattedId = currency;
                if (throwsOnFormat) {
                    throw new IllegalStateException("format failure");
                }
            }
            return new Balance(result, wantsFormatting ? formatted : null);
        }
    }

    @Test
    void balanceMatchesExactDeclaredIdsAndRejectsAllEncodingCollisions() {
        var bridge = new BalanceBridge();
        NamespacedId id = NamespacedId.of("shop_pack", "gold_coins");
        bridge.ids.add(id);
        var resolver = new VariablePlaceholders(api, () -> GuiSessionPort.NONE, bridge, d -> { });
        assertThat(resolver.resolve("balance_shop_pack_gold_coins", STEVE, n -> Optional.empty())).isEqualTo("0");
        assertThat(bridge.readId).isEqualTo(id);
        assertThat(bridge.readViewer).isEqualTo(STEVE);
        assertThat(resolver.resolve("balance_shop_pack_gold_coins_formatted", STEVE, n -> Optional.empty()))
                .isEqualTo("formatted");
        assertThat(bridge.formattedId).isEqualTo(id);
        assertThat(resolver.resolve("balance_shop_pack_gold", STEVE, n -> Optional.empty())).isNull();
        bridge.ids.add(NamespacedId.of("shop", "pack_gold_coins"));
        assertThat(resolver.resolve("balance_shop_pack_gold_coins", STEVE, n -> Optional.empty())).isNull();
        assertThat(resolver.resolve("balance_shop_pack_gold_coins_formatted", STEVE, n -> Optional.empty())).isNull();
        bridge.ids.remove(1);
        bridge.ids.add(NamespacedId.of("shop_pack", "gold_coins_formatted"));
        assertThat(resolver.resolve("balance_shop_pack_gold_coins_formatted", STEVE, n -> Optional.empty())).isNull();
        assertThat(bridge.reads).isEqualTo(2);
    }

    @Test
    void rawBalancesUsePlainFiniteDecimalAndTheBridgeIsQueriedEveryTime() {
        var bridge = new BalanceBridge();
        bridge.ids.add(NamespacedId.of("shop", "gold"));
        var resolver = new VariablePlaceholders(api, () -> GuiSessionPort.NONE, bridge, d -> { });
        double[] values = {0, -12.5, 1e-12, 1e20, Double.MIN_VALUE};
        String[] expected = {"0", "-12.5", "0.000000000001", "100000000000000000000",
                java.math.BigDecimal.valueOf(Double.MIN_VALUE).toPlainString()};
        for (int i = 0; i < values.length; i++) {
            bridge.result = new EconomyResult.Succeeded(values[i]);
            assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isEqualTo(expected[i]);
        }
        assertThat(bridge.reads).isEqualTo(values.length);
        bridge.ids.clear();
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isNull();
        assertThat(bridge.reads).isEqualTo(values.length);
    }

    @Test
    void failuresNeverBecomeBalancesAndDiagnosticsAreRateLimitedPerCurrency() {
        var bridge = new BalanceBridge();
        NamespacedId gold = NamespacedId.of("shop", "gold");
        bridge.ids.add(gold);
        bridge.ids.add(NamespacedId.of("shop", "silver"));
        var diagnostics = new ArrayList<Diagnostic>();
        var clock = new AtomicLong(1000);
        var resolver = new VariablePlaceholders(api, () -> GuiSessionPort.NONE, bridge, diagnostics::add, clock::get);
        assertThat(resolver.resolve("balance_shop_gold", null, n -> Optional.empty())).isEmpty();
        assertThat(bridge.reads).isZero();
        bridge.result = new EconomyResult.Failed("not installed");
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isEmpty();
        assertThat(diagnostics).hasSize(1);
        assertThat(diagnostics.get(0).code()).isEqualTo(DiagnosticCodes.ACTION_FAILED);
        assertThat(diagnostics.get(0).resource()).isEqualTo(gold);
        assertThat(resolver.resolve("balance_shop_silver", STEVE, n -> Optional.empty())).isEmpty();
        assertThat(diagnostics).hasSize(2);
        clock.addAndGet(1000);
        bridge.result = new EconomyResult.InsufficientFunds(100);
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isEmpty();
        bridge.result = new EconomyResult.Succeeded(Double.POSITIVE_INFINITY);
        clock.addAndGet(1000);
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isEmpty();
        bridge.result = null;
        clock.addAndGet(1000);
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isEmpty();
        bridge.throwsOnRead = true;
        clock.addAndGet(1000);
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isEmpty();
        assertThat(diagnostics.get(diagnostics.size() - 1).message()).contains("provider removed");
        bridge.throwsOnRead = false;
        bridge.result = new EconomyResult.Succeeded(12);
        bridge.formatted = null;
        clock.addAndGet(1000);
        assertThat(resolver.resolve("balance_shop_gold_formatted", STEVE, n -> Optional.empty())).isEmpty();
        bridge.throwsOnFormat = true;
        clock.addAndGet(1000);
        assertThat(resolver.resolve("balance_shop_gold_formatted", STEVE, n -> Optional.empty())).isEmpty();
        bridge.throwsOnCatalog = true;
        assertThat(resolver.resolve("balance_shop_gold", STEVE, n -> Optional.empty())).isNull();
        assertThat(diagnostics.get(diagnostics.size() - 1).message()).contains("catalog unavailable");
        bridge.throwsOnCatalog = false;
        bridge.formatted = "new provider";
        bridge.throwsOnFormat = false;
        assertThat(resolver.resolve("balance_shop_gold_formatted", STEVE, n -> Optional.empty()))
                .isEqualTo("new provider");
    }

    @Test
    void formattedReadRejectsReplacementInsteadOfCombiningDifferentBindings() {
        var id = NamespacedId.of("shop", "gold");
        var diagnostics = new ArrayList<Diagnostic>();
        var bridge = new CurrencyPlaceholderBridge() {
            String binding = "old";

            @Override
            public Collection<NamespacedId> currencies() {
                return List.of(id);
            }

            @Override
            public Balance read(NamespacedId currency, UUID viewer, boolean formatted) {
                String readBinding = binding;
                double amount = 1250;
                binding = "replacement";
                if (!readBinding.equals(binding)) {
                    return new Balance(new EconomyResult.Failed("currency binding replaced"), null);
                }
                return new Balance(new EconomyResult.Succeeded(amount), formatted ? readBinding + amount : null);
            }
        };
        var resolver = new VariablePlaceholders(api, () -> GuiSessionPort.NONE, bridge, diagnostics::add);
        assertThat(resolver.resolve("balance_shop_gold_formatted", STEVE, n -> Optional.empty())).isEmpty();
        assertThat(diagnostics).singleElement().extracting(Diagnostic::message).asString()
                .contains("currency binding replaced");
    }

    @Test
    void wholeNumbersPrintWithoutAFraction() {
        assertThat(VariablePlaceholders.text(4.0)).isEqualTo("4");
        assertThat(VariablePlaceholders.text(4.5)).isEqualTo("4.5");
        assertThat(VariablePlaceholders.text(true)).isEqualTo("true");
    }
}
