package dev.materione.api.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class VariableRefTest {

    @Nested
    class Spellings {

        @Test
        void aBareNameIsLocalWhateverIsDeclared() {
            assertThat(VariableRef.parse("count", "shop")).isEqualTo(new VariableRef.Local("count"));
            assertThat(VariableRef.parse("local.count", "shop")).isEqualTo(new VariableRef.Local("count"));
        }

        @Test
        void sessionNamesAreTheSessionScope() {
            assertThat(VariableRef.parse("session.page", "shop")).isEqualTo(new VariableRef.Session("page"));
        }

        @Test
        void aScopedNameResolvesInTheOwnPackOrNamesItsPack() {
            assertThat(VariableRef.parse("player.coins", "shop"))
                    .isEqualTo(new VariableRef.Persistent(VariableScope.PLAYER, NamespacedId.of("shop", "coins")));
            assertThat(VariableRef.parse("server.events.season", "shop"))
                    .isEqualTo(new VariableRef.Persistent(VariableScope.SERVER, NamespacedId.of("events", "season")));
        }

        @Test
        void aQualifiedNameTakesItsScopeFromTheDeclaration() {
            assertThat(VariableRef.parse("shop:coins", "other"))
                    .isEqualTo(new VariableRef.Persistent(null, NamespacedId.of("shop", "coins")));
        }

        @Test
        void aScopedNameWithoutAnOwnPackNeedsItsPack() {
            assertThatThrownBy(() -> VariableRef.parse("player.coins", null))
                    .hasMessageContaining("names no variable");
        }
    }

    @Nested
    class Rejections {

        @Test
        void theScopeWordsCannotBeLocalNames() {
            for (String word : VariableRef.RESERVED) {
                assertThatThrownBy(() -> VariableRef.parse(word, "shop"))
                        .hasMessageContaining("reserved scope word");
            }
            assertThatThrownBy(() -> VariableRef.parse("local.player", "shop"))
                    .hasMessageContaining("reserved scope word");
        }

        @Test
        void aLocalNameHasNoDots() {
            assertThatThrownBy(() -> VariableRef.parse("a.b.c", "shop"))
                    .hasMessageContaining("a local name has no dots");
        }

        @Test
        void blankAndMalformedNamesAreRefused() {
            assertThatThrownBy(() -> VariableRef.parse(" ", "shop")).hasMessage("variable name is required");
            assertThatThrownBy(() -> VariableRef.parse(null, "shop")).hasMessage("variable name is required");
            assertThatThrownBy(() -> VariableRef.parse("Bad Id:coins", "shop"))
                    .hasMessageContaining("not a pack:key");
            assertThatThrownBy(() -> VariableRef.parse("player.a.b.c", "shop"))
                    .hasMessageContaining("names no variable");
        }
    }
}
