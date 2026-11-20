package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.rule.ParsedRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An item-scoped variable is the item stack of the run (logic.md §38), so a trigger that provides no {@code item} root
 * cannot name one: a read and a declared write are both the unknown-root warning (MTR-0060) at load.
 */
class ItemScopeRootsTest {

    /** An action that names an item-scoped variable the way set_var does, without a template. */
    private static final class WritesItemVariable implements ActionType<String> {
        @Override
        public String id() {
            return "item_write";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Item write", "A test action",
                    List.of(ParameterSchema.of("value", ParameterType.STRING, false)), "1.0.0"),
                    LogicCategory.DATA, "value");
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            params.requireRoot("item", "item_write writes the item variable rpg:level");
            return params.text("value");
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.OK;
        }
    }

    private final FlowFixture f = new FlowFixture();

    ItemScopeRootsTest() {
        f.logic.action(new WritesItemVariable());
    }

    private static List<String> codes(ParsedRules parsed) {
        return parsed.problems().stream().map(problem -> problem.code().code()).toList();
    }

    @Test
    void aTriggerOnAnItemProvidesTheStackSoAReadIsClean() {
        ParsedRules parsed = f.compile("""
                - on: click
                  if: "vars.item.rpg.level > 1"
                  do:
                    - say: "level ${vars.item.rpg.level}"
                    - item_write: x
                """);

        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
    }

    @Test
    void aTriggerWithNoItemCannotNameAStackSoAReadIsMtr0060() {
        ParsedRules parsed = f.compile("""
                - on: place
                  do:
                    - say: "level ${vars.item.rpg.level}"
                """);

        assertThat(parsed.rules()).hasSize(1); // the step still loads, as an unknown root always does
        assertThat(parsed.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.code().code()).isEqualTo("MTR-0060");
            assertThat(problem.message()).contains("'item'").contains("'place' trigger");
            assertThat(problem.hint()).contains("vars.item.*").contains("item stack");
            assertThat(problem.target().field()).containsExactly("behavior", "0", "do", "0");
        });
    }

    @Test
    void aConditionReadIsCheckedToo() {
        ParsedRules parsed = f.compile("""
                - on: place
                  if: "vars.item.rpg.level > 1"
                  do: [ { a: x } ]
                """);

        assertThat(codes(parsed)).containsExactly("MTR-0060");
        assertThat(parsed.problems().get(0).target().field()).containsExactly("behavior", "0", "if");
    }

    @Test
    void aDeclaredWriteThatIsNotATemplateIsCheckedThroughTheReader() {
        ParsedRules onItem = f.compile("- on: click\n  do: [ { item_write: x } ]\n");
        assertThat(onItem.problems()).isEmpty();

        ParsedRules noItem = f.compile("- on: place\n  do: [ { item_write: x } ]\n");
        assertThat(codes(noItem)).containsExactly("MTR-0060");
        assertThat(noItem.problems().get(0).message())
                .contains("'item'").contains("item_write writes the item variable rpg:level");
        assertThat(noItem.problems().get(0).target().field()).containsExactly("behavior", "0", "do", "0");
    }

    @Test
    void otherScopesAndLocalsNeverAskForAStack() {
        ParsedRules parsed = f.compile("""
                - on: place
                  do:
                    - say: "${vars.player.shop.coins} ${vars.server.shop.season} ${vars.count} ${vars.local.x}"
                """);

        assertThat(parsed.problems()).isEmpty();
    }
}
