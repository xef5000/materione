package dev.materione.plugin.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The typed parse of an open block's arguments (logic.md §6.1, stage 3) and the usage line it answers with.
 * Pure: the plan is built directly, so every rule is checked without a registry or a server.
 */
class ArgParserTest {

    private static final PlayerNames PLAYERS = new PlayerNames() {
        @Override
        public Optional<String> online(String name) {
            return name.equalsIgnoreCase("Alice") ? Optional.of("Alice") : Optional.empty();
        }

        @Override
        public Collection<String> names() {
            return List.of("Alice");
        }
    };

    private static final OpenPlan PLAN = new OpenPlan(
            List.of("shop"),
            List.of(
                    new OpenArgSpec("category", ArgType.CHOICE, false, "all", List.of("all", "tools", "food")),
                    new OpenArgSpec("amount", ArgType.INT, true, null, List.of()),
                    new OpenArgSpec("ratio", ArgType.NUMBER, true, null, List.of()),
                    new OpenArgSpec("vip", ArgType.BOOLEAN, true, null, List.of()),
                    new OpenArgSpec("target", ArgType.PLAYER, true, null, List.of())),
            null, null, false, null, List.of());

    @Test
    void defaultsFillInAndValuesReadInCanonicalText() {
        ArgParser.Result result = ArgParser.parse(PLAN, Map.of("amount", "007", "vip", "TRUE"), PLAYERS);

        assertThat(result.ok()).isTrue();
        assertThat(result.values()).containsExactly(
                Map.entry("category", "all"), Map.entry("amount", "7"), Map.entry("vip", "true"));
    }

    @Test
    void aPlayerNameResolvesToTheOnlinePlayersOwnSpelling() {
        ArgParser.Result result = ArgParser.parse(PLAN, Map.of("target", "alice"), PLAYERS);

        assertThat(result.values()).containsEntry("target", "Alice");
    }

    @Test
    void everyBadValueIsAUsageProblemNamingTheArgument() {
        assertThat(ArgParser.parse(PLAN, Map.of("amount", "abc"), PLAYERS).error())
                .isEqualTo("amount: 'abc' is not a whole number");
        assertThat(ArgParser.parse(PLAN, Map.of("ratio", "nan"), PLAYERS).error())
                .isEqualTo("ratio: 'nan' is not a number");
        assertThat(ArgParser.parse(PLAN, Map.of("vip", "maybe"), PLAYERS).error())
                .isEqualTo("vip: 'maybe' is not true or false");
        assertThat(ArgParser.parse(PLAN, Map.of("category", "bogus"), PLAYERS).error())
                .isEqualTo("category: 'bogus' is not one of all, tools, food");
        assertThat(ArgParser.parse(PLAN, Map.of("target", "Bob"), PLAYERS).error())
                .isEqualTo("target: no online player named 'Bob'");
    }

    @Test
    void anUndeclaredNameIsAnErrorNotIgnored() {
        assertThat(ArgParser.parse(PLAN, Map.of("colour", "red"), PLAYERS).error())
                .isEqualTo("unknown argument 'colour'");
    }

    @Test
    void aRequiredArgumentWithNoDefaultMustBePassed() {
        OpenPlan plan = new OpenPlan(List.of("give"),
                List.of(new OpenArgSpec("item", ArgType.STRING, false, null, List.of())),
                null, null, false, null, List.of());

        assertThat(ArgParser.parse(plan, Map.of(), PLAYERS).error()).isEqualTo("missing item");
        assertThat(ArgParser.parse(plan, Map.of("item", "gem"), PLAYERS).values()).containsEntry("item", "gem");
    }

    @Test
    void theUsageLineNamesEveryArgumentWithItsTypeAndDefault() {
        assertThat(OpenUsage.of("/shop", PLAN)).isEqualTo(
                "usage: /shop [category:all|tools|food=all] [amount:int] [ratio:number] [vip:boolean] "
                        + "[target:player]");
    }

    @Test
    void aRequiredArgumentIsShownInAngleBrackets() {
        OpenPlan plan = new OpenPlan(List.of("give"),
                List.of(new OpenArgSpec("item", ArgType.STRING, false, null, List.of())),
                null, null, false, null, List.of());

        assertThat(OpenUsage.refusal("/give", plan, "missing item"))
                .isEqualTo("missing item. usage: /give <item:string>");
    }

    /** A command document's {@code args.*} are typed for the expression engine; a GUI's {@code gui.args} stay text. */
    @Test
    void typedValuesAreNumbersAndBooleansForTheExpressionEngineAndTextOtherwise() {
        ArgParser.Result result = ArgParser.parse(PLAN,
                Map.of("amount", "007", "ratio", "2.5", "vip", "TRUE", "target", "alice"), PLAYERS);

        Map<String, Object> typed = ArgParser.typed(PLAN, result.values());

        assertThat(typed).containsEntry("category", "all").containsEntry("amount", 7).containsEntry("ratio", 2.5)
                .containsEntry("vip", true).containsEntry("target", "Alice");
        assertThat(typed.get("amount")).isInstanceOf(Integer.class);
        assertThat(typed.get("ratio")).isInstanceOf(Double.class);
        assertThat(typed.keySet()).containsExactly("category", "amount", "ratio", "vip", "target");
        // text is the safe reading of anything that does not read as its type (a dropped default never gets here)
        assertThat(ArgParser.typed(PLAN, Map.of("amount", "x"))).containsEntry("amount", "x");
        assertThat(ArgParser.typed(PLAN, Map.of("undeclared", "y"))).containsEntry("undeclared", "y");
    }

    @Test
    void theArgumentTypesReadTheirAuthorWords() {
        assertThat(ArgType.of("int")).contains(ArgType.INT);
        assertThat(ArgType.of("choice")).contains(ArgType.CHOICE);
        assertThat(ArgType.of("float")).isEmpty();
        assertThat(ArgType.INT.parse("-3", List.of(), PLAYERS).value()).isEqualTo("-3");
        assertThat(ArgType.NUMBER.parse("Infinity", List.of(), PLAYERS).ok()).isFalse();
    }
}
