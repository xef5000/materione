package dev.materione.integrations.variables;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.logic.variable.GuiSessionPort;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.api.logic.variable.VariablesApi;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    void theBalanceSeamIsNotImplementedYet() {
        // currencies (#233) add their branch beside var_; until then the token is left alone
        assertThat(resolve("balance_shop_gold", STEVE)).isNull();
        assertThat(resolve("balance_shop_gold_formatted", STEVE)).isNull();
    }

    @Test
    void wholeNumbersPrintWithoutAFraction() {
        assertThat(VariablePlaceholders.text(4.0)).isEqualTo("4");
        assertThat(VariablePlaceholders.text(4.5)).isEqualTo("4.5");
        assertThat(VariablePlaceholders.text(true)).isEqualTo("true");
    }
}
