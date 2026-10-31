package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.SessionVariables;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableFacets;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The scopes of {@code vars.*} as an expression reads them (logic.md §3.3). */
class MapLogicContextVariablesTest {

    private static final NamespacedId COINS = NamespacedId.of("shop", "coins");
    private static final NamespacedId SEASON = NamespacedId.of("shop", "season");

    private final VariableDeclaration coins = new VariableDeclaration(
            COINS, VariableScope.PLAYER, VariableType.NUMBER, 10, "Shop coins");
    private final VariableDeclaration season = new VariableDeclaration(
            SEASON, VariableScope.SERVER, VariableType.STRING, "spring", null);

    /** A binding over plain maps: the stored values of the player and of the server. */
    private static final class FakeBinding implements VariableBinding {
        final Map<NamespacedId, Object> playerValues = new HashMap<>();
        final Map<NamespacedId, Object> serverValues = new HashMap<>();
        final Map<NamespacedId, VariableDeclaration> declared = new HashMap<>();
        boolean holder = true;

        @Override
        public Optional<VariableDeclaration> declaration(NamespacedId id) {
            return Optional.ofNullable(declared.get(id));
        }

        @Override
        public Optional<Object> stored(VariableDeclaration declaration) {
            if (declaration.scope() == VariableScope.PLAYER && !holder) {
                return Optional.empty();
            }
            Map<NamespacedId, Object> values =
                    declaration.scope() == VariableScope.PLAYER ? playerValues : serverValues;
            return Optional.ofNullable(values.get(declaration.id()));
        }

        @Override
        public boolean store(VariableDeclaration declaration, Object value) {
            return false;
        }
    }

    private FakeBinding binding() {
        var binding = new FakeBinding();
        binding.declared.put(COINS, coins);
        binding.declared.put(SEASON, season);
        return binding;
    }

    @Nested
    class Locals {

        @Test
        void aBareNameAndTheLocalScopeReadTheSameLocal() {
            var context = new MapLogicContext(Map.of());
            context.locals().put("count", 3.0);

            assertThat(context.value("vars.count")).isEqualTo(3.0);
            assertThat(context.value("vars.local.count")).isEqualTo(3.0);
            assertThat(context.value("vars.local.missing")).isNull();
        }
    }

    @Nested
    class Persistent {

        @Test
        void aDeclaredVariableNeverWrittenReadsItsDefault() {
            var context = new MapLogicContext(Map.of()).withVariables(binding());

            assertThat(context.value("vars.player.shop.coins")).isEqualTo(10.0);
            assertThat(context.value("vars.server.shop.season")).isEqualTo("spring");
        }

        @Test
        void aStoredValueWinsOverTheDefault() {
            var binding = binding();
            binding.playerValues.put(COINS, 42.0);
            binding.serverValues.put(SEASON, "winter");
            var context = new MapLogicContext(Map.of()).withVariables(binding);

            assertThat(context.value("vars.player.shop.coins")).isEqualTo(42.0);
            assertThat(context.value("vars.server.shop.season")).isEqualTo("winter");
        }

        @Test
        void anUndeclaredVariableIsAbsentNotAKey() {
            var context = new MapLogicContext(Map.of()).withVariables(binding());

            assertThat(context.value("vars.player.shop.gems")).isNull();
            assertThat(context.value("vars.player.coins")).isNull();
        }

        @Test
        void aScopeMustMatchTheDeclaration() {
            var context = new MapLogicContext(Map.of()).withVariables(binding());

            assertThat(context.value("vars.server.shop.coins")).isNull();
            assertThat(context.value("vars.player.shop.season")).isNull();
        }

        @Test
        void aPlayerVariableWithNoHolderReadsItsDefault() {
            var binding = binding();
            binding.holder = false;
            var context = new MapLogicContext(Map.of()).withVariables(binding);

            assertThat(context.value("vars.player.shop.coins")).isEqualTo(10.0);
        }

        @Test
        void withoutABindingNothingIsDeclared() {
            var context = new MapLogicContext(Map.of());

            assertThat(context.value("vars.player.shop.coins")).isNull();
            assertThat(context.variables()).isSameAs(VariableBinding.NONE);
        }
    }

    @Nested
    class Session {

        @Test
        void aSessionVariableReadsThroughTheSessionFacet() {
            Map<String, Object> store = new HashMap<>(Map.of("page", 2.0));
            SessionVariables session = new SessionVariables() {
                @Override
                public Optional<Object> get(String name) {
                    return Optional.ofNullable(store.get(name));
                }

                @Override
                public void put(String name, Object value) {
                    store.put(name, value);
                }
            };
            var context = new MapLogicContext(Map.of()).with(VariableFacets.SESSION, session);

            assertThat(context.value("vars.session.page")).isEqualTo(2.0);
            assertThat(context.value("vars.session.missing")).isNull();
        }

        @Test
        void withNoSessionTheSessionScopeIsEmpty() {
            var context = new MapLogicContext(Map.of());

            assertThat(context.value("vars.session.page")).isNull();
        }
    }
}
