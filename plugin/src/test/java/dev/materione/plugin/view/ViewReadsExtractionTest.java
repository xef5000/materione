package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.GuiReads;
import dev.materione.plugin.gui.rules.GuiRuleDiagnoser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Read extraction from a compiled GUI (views.md section 5.2): every template, condition, item source and list source
 * of the definition contributes the declared variables it reads, a read that cannot be placed is polled and reported,
 * and an undeclared session read is {@code MTR-0063} at load (acceptance case 2, owner answer Q9).
 */
class ViewReadsExtractionTest {

    private GuiFixture f;

    @BeforeEach
    void declare() {
        f = new GuiFixture();
        f.variables.declare("demo", "coins", VariableScope.PLAYER, 0d);
        f.variables.declare("demo", "vip", VariableScope.PLAYER, false);
        f.variables.declare("demo", "favorite", VariableScope.PLAYER, "minecraft:stone");
        f.variables.declare("demo", "playlist", VariableScope.PLAYER, "a,b");
        f.variables.declare("demo", "season", VariableScope.SERVER, "spring");
        f.variables.declare("demo", "tab", VariableScope.SESSION, "all");
        f.variables.declare("demo", "search", VariableScope.SESSION, "");
    }

    private static ReadKey key(VariableScope scope, String variable) {
        return new ReadKey(scope, NamespacedId.of("demo", variable));
    }

    private ViewReads reads(String layers) {
        var live = f.gui("""
                menu:
                  title: Menu
                  container: chest_3
                  layers:
                """ + layers);
        return GuiReads.of(live, f.logic.lookup()).reads();
    }

    private java.util.List<Diagnostic> problems(String layers) {
        var definition = f.definition("""
                menu:
                  title: Menu
                  container: chest_3
                  layers:
                """ + layers);
        return new GuiRuleDiagnoser(f.logic::lookup).checkLayers(definition);
    }

    @Test
    void anItemLayersTemplatesAreContentReads() {
        var reads = reads("""
                    - id: shown
                      type: item
                      slot: 0
                      item: minecraft:stone
                      amount: "${vars.player.demo.coins}"
                      name: "Season ${vars.server.demo.season}"
                      lore: ["Tab ${vars.session.demo.tab}"]
                """);

        assertThat(reads.keys()).containsOnlyKeys(
                key(VariableScope.PLAYER, "coins"), key(VariableScope.SERVER, "season"),
                key(VariableScope.SESSION, "tab"));
        assertThat(reads.keys().get(key(VariableScope.PLAYER, "coins"))).containsExactly(ReadRole.CONTENT);
        assertThat(reads.polled()).isFalse();
    }

    @Test
    void aLayersExpressionAndVarConditionsAreReads() {
        var reads = reads("""
                    - id: expr
                      type: item
                      slot: 0
                      item: minecraft:stone
                      visible_if: "vars.session.demo.tab == 'all' && vars.player.demo.coins > 5"
                    - id: cond
                      type: item
                      slot: 1
                      item: minecraft:stone
                      visible_if:
                        var: { var: player.vip, value: true }
                """);

        assertThat(reads.keys()).containsOnlyKeys(
                key(VariableScope.SESSION, "tab"), key(VariableScope.PLAYER, "coins"),
                key(VariableScope.PLAYER, "vip"));
    }

    @Test
    void anItemSourcesTemplateIsARead() {
        var reads = reads("""
                    - id: dyn
                      type: item
                      slot: 0
                      item: "${vars.player.demo.favorite}"
                """);

        assertThat(reads.keys()).containsOnlyKeys(key(VariableScope.PLAYER, "favorite"));
    }

    @Test
    void aListSourcesVariableItsSearchAndItsEntryTemplateAreReads() {
        var reads = reads("""
                    - id: names
                      type: list
                      slots: { from: 9, to: 11 }
                      source: { type: variable, var: "demo:playlist" }
                      filter: "${vars.server.demo.season == 'spring'}"
                      search: { fields: [value] }
                      entry:
                        item: minecraft:paper
                        name: "${entry.value} ${vars.player.demo.coins}"
                """);

        assertThat(reads.keys()).containsOnlyKeys(
                key(VariableScope.PLAYER, "playlist"), key(VariableScope.SERVER, "season"),
                key(VariableScope.SESSION, "search"), key(VariableScope.PLAYER, "coins"));
    }

    @Test
    void whatNoWriteAnnouncesIsPolledAndNotInTheIndex() {
        var reads = reads("""
                    - id: facts
                      type: item
                      slot: 0
                      item: minecraft:stone
                      name: "${player.level} %vault_eco_balance%"
                """);

        assertThat(reads.keys()).isEmpty();
        assertThat(reads.polled()).isTrue();
    }

    @Test
    void aVariablePathThatCannotBePlacedIsPolledAndReportedOnceAsInfo() {
        var layers = """
                    - id: loose
                      type: item
                      slot: 0
                      item: minecraft:stone
                      name: "${vars.player.coins} ${vars.player.coins}"
                    - id: again
                      type: item
                      slot: 1
                      item: minecraft:stone
                      name: "${vars.player.coins}"
                """;

        assertThat(reads(layers).polled()).isTrue();
        assertThat(problems(layers)).filteredOn(problem -> problem.code() == DiagnosticCodes.VIEW_READ_UNPLACED)
                .hasSize(1)
                .allSatisfy(problem -> assertThat(problem.severity())
                        .isEqualTo(dev.materione.api.diagnostics.Severity.INFO));
    }

    @Test
    void anUndeclaredSessionReadIsMtr0063ForATemplateAConditionAndAListSearch() {
        var problems = problems("""
                    - id: template
                      type: item
                      slot: 0
                      item: minecraft:stone
                      name: "${vars.session.demo.typo}"
                    - id: cond
                      type: item
                      slot: 1
                      item: minecraft:stone
                      visible_if: "vars.session.demo.gone == true"
                    - id: names
                      type: list
                      slots: { from: 9, to: 11 }
                      source: { entries: [{ name: Ada }] }
                      search: { var: nosuch, fields: [name] }
                      entry: { item: minecraft:paper }
                """);

        assertThat(problems).filteredOn(problem -> problem.code() == DiagnosticCodes.VARIABLE_UNDECLARED)
                .extracting(Diagnostic::message)
                .anySatisfy(message -> assertThat(message).contains("typo"))
                .anySatisfy(message -> assertThat(message).contains("gone"))
                .anySatisfy(message -> assertThat(message).contains("nosuch"));
    }

    @Test
    void aDeclaredSessionReadIsNotAProblem() {
        assertThat(problems("""
                    - id: ok
                      type: item
                      slot: 0
                      item: minecraft:stone
                      name: "${vars.session.demo.tab}"
                """)).isEmpty();
    }

    @Test
    void theIndexMapsEachFamilyToItsReadersAndAReloadRebuildsIt() {
        f.gui("""
                menu:
                  title: Menu
                  container: chest_3
                  layers:
                    - { id: a, type: item, slot: 0, item: minecraft:stone, name: "${vars.player.demo.coins}" }
                """);
        var first = ReadsIndex.of(GuiReads.of(f.view(), f.logic.lookup()));
        var readers = first.readers(VariableScope.PLAYER, NamespacedId.of("demo", "coins"));
        assertThat(readers).containsExactly(
                new ReadsIndex.Reader(new ViewRef("gui", GuiFixture.id("menu")), ReadRole.CONTENT));
        assertThat(first.readers(VariableScope.SERVER, NamespacedId.of("demo", "season"))).isNull();

        f.gui("""
                menu:
                  title: Menu
                  container: chest_3
                  layers:
                    - { id: a, type: item, slot: 0, item: minecraft:stone, name: "${vars.server.demo.season}" }
                """);
        var second = ReadsIndex.of(GuiReads.of(f.view(), f.logic.lookup()));

        assertThat(second.readers(VariableScope.PLAYER, NamespacedId.of("demo", "coins"))).isNull();
        assertThat(second.readers(VariableScope.SERVER, NamespacedId.of("demo", "season"))).hasSize(1);
        assertThat(first.readers(VariableScope.PLAYER, NamespacedId.of("demo", "coins")))
                .as("the old index is immutable").hasSize(1);
        assertThat(second.families()).isEqualTo(1);
    }
}
