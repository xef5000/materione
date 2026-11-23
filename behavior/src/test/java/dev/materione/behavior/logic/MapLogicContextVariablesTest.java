package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
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
    class ItemScope {

        private final NamespacedId level = NamespacedId.of("rpg", "level");
        private final VariableDeclaration declaration =
                new VariableDeclaration(level, VariableScope.ITEM, VariableType.NUMBER, 1, "Weapon level");

        /** A binding whose one declaration is item-scoped, with the stored value of the run's stack. */
        private VariableBinding binding(Object stored) {
            return new VariableBinding() {
                @Override
                public Optional<VariableDeclaration> declaration(NamespacedId id) {
                    return id.equals(level) ? Optional.of(declaration) : Optional.empty();
                }

                @Override
                public Optional<Object> stored(VariableDeclaration found) {
                    return Optional.ofNullable(stored);
                }

                @Override
                public boolean store(VariableDeclaration found, Object value) {
                    return false;
                }
            };
        }

        @Test
        void anItemVariableReadsTheStacksValueOrItsDefault() {
            assertThat(new MapLogicContext(Map.of()).withVariables(binding(null)).value("vars.item.rpg.level"))
                    .isEqualTo(1.0);
            assertThat(new MapLogicContext(Map.of()).withVariables(binding(4.0)).value("vars.item.rpg.level"))
                    .isEqualTo(4.0);
        }

        @Test
        void theScopeMustMatchTheDeclarationAndTheNameMustBeDeclared() {
            var context = new MapLogicContext(Map.of()).withVariables(binding(4.0));

            assertThat(context.value("vars.player.rpg.level")).isNull();
            assertThat(context.value("vars.item.rpg.xp")).isNull();
            assertThat(context.value("vars.item.level")).isNull(); // reads spell the pack (logic.md section 20 decision 6)
        }

        @Test
        void theItemRootOfTheTreeIsNotTheItemScope() {
            var context = new MapLogicContext(Map.of("item", Map.of("id", "rpg:sword")))
                    .withVariables(binding(4.0));

            assertThat(context.value("item.id")).isEqualTo("rpg:sword");
            assertThat(context.value("vars.item.rpg.level")).isEqualTo(4.0);
        }
    }

    @Nested
    class SessionScope {

        private final NamespacedId page = NamespacedId.of("ui", "page");
        private final VariableDeclaration declaration =
                new VariableDeclaration(page, VariableScope.SESSION, VariableType.NUMBER, 1, "Window page");

        private VariableBinding binding(Object stored) {
            return new VariableBinding() {
                @Override
                public Optional<VariableDeclaration> declaration(NamespacedId id) {
                    return id.equals(page) ? Optional.of(declaration) : Optional.empty();
                }

                @Override
                public Optional<Object> stored(VariableDeclaration found) {
                    return Optional.ofNullable(stored);
                }

                @Override
                public boolean store(VariableDeclaration found, Object value) {
                    return false;
                }
            };
        }

        @Test
        void aSessionVariableReadsTheWindowsValueOrItsDefault() {
            assertThat(new MapLogicContext(Map.of()).withVariables(binding(2.0)).value("vars.session.ui.page"))
                    .isEqualTo(2.0);
            assertThat(new MapLogicContext(Map.of()).withVariables(binding(null)).value("vars.session.ui.page"))
                    .isEqualTo(1.0);
        }

        @Test
        void theScopeMustMatchTheDeclarationAndTheNameMustBeDeclared() {
            var context = new MapLogicContext(Map.of()).withVariables(binding(2.0));

            assertThat(context.value("vars.player.ui.page")).isNull();
            assertThat(context.value("vars.session.ui.missing")).isNull();
            assertThat(context.value("vars.session.page")).isNull(); // reads spell the pack
        }

        @Test
        void withNoBindingTheSessionScopeIsEmpty() {
            assertThat(new MapLogicContext(Map.of()).value("vars.session.ui.page")).isNull();
        }
    }
}

