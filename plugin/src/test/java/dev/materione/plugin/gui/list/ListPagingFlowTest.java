package dev.materione.plugin.gui.list;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.gui.ClickArea;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.input.ClickKind;
import dev.materione.plugin.gui.input.ClickRouter;
import dev.materione.plugin.gui.input.GuiClick;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.render.SlotSource;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A paged, filtered list end to end below the Bukkit event (logic.md §6.7): an authored GUI is compiled by the real
 * GUI and rule compilers, a session is opened and rendered by the real renderer, and clicks are routed. Covers the
 * filter and sort before paging, an entry's click running one rule with that entry, {@code gui.page} moving the list
 * and firing its trigger, a click on an empty slot running nothing, and a step past the last page doing nothing.
 */
class ListPagingFlowTest {

    /** Five entries; Bob is filtered out, the four others sort by name and fill two pages of three. */
    private static final String ROSTER = """
            roster:
              title: Roster
              container: chest_3
              layers:
                - id: players
                  type: list
                  slots: { from: 9, to: 11 }
                  source:
                    entries:
                      - { name: Ada, level: 3 }
                      - { name: Bob, level: 1 }
                      - { name: Cy, level: 5 }
                      - { name: Dee, level: 2 }
                      - { name: Eve, level: 4 }
                  filter: "${entry.level > 1}"
                  sort: name
                  entry:
                    item: "${entry.name}"
              behavior:
                - on: gui.click
                  layer: players
                  do:
                    - mark: "picked ${entry.name} (slot ${click.slot})"
                - on: gui.click
                  slot: 22
                  do:
                    - gui.page: { list: players, to: next }
                - on: gui.page
                  layer: players
                  do:
                    - mark: "page ${page.index} of ${page.count}"
            """;

    private GuiFixture f;
    private GuiSession session;

    @BeforeEach
    void open() {
        f = new GuiFixture();
        f.windows.renderer = new GuiRenderer(RenderReporter.SILENT);
        f.gui(ROSTER);
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("roster"), Map.of(),
                OpenRequest.Cause.API)).session();
    }

    /**
     * The name of the entry a slot shows, read from the render's record of what each list slot shows (the item of
     * the entry has no content here, so it is a barrier, and the barrier does not name the entry). Null when the
     * slot shows no entry.
     */
    private String shown(int slot) {
        return session.rendered().lists().shownAt(slot).map(shown -> String.valueOf(shown.entry().get("name")))
                .orElse(null);
    }

    private ClickRouter.Routed click(int slot) {
        return f.router.route(new GuiClick(f.viewer, session, slot, ClickKind.LEFT, 0));
    }

    @Test
    void theFirstPageHoldsTheFilteredAndSortedEntriesAndTheRestOfTheSlotsAreEmpty() {
        assertThat(shown(9)).contains("Ada");
        assertThat(shown(10)).contains("Cy");
        assertThat(shown(11)).contains("Dee");
        assertThat(session.rendered().slots().keySet()).containsExactlyInAnyOrder(9, 10, 11);
        assertThat(session.pages()).containsEntry("players", Map.of("number", 1, "count", 2, "size", 4));
        assertThat(session.rendered().lists().shownAt(10).orElseThrow().entry()).containsEntry("name", "Cy");
    }

    @Test
    void aClickOnAnEntryRunsOneRuleWithThatEntry() {
        ClickRouter.Routed routed = click(10);

        assertThat(routed.layer()).isEqualTo("players");
        assertThat(f.ran).containsExactly("mark:picked Cy (slot 10)");
    }

    @Test
    void guiPageMovesTheListToTheNextPageAndFiresItsTriggerAfterTheWindowShowsIt() {
        assertThat(click(22).handled()).isTrue();

        assertThat(session.pages()).containsEntry("players", Map.of("number", 2, "count", 2, "size", 4));
        assertThat(shown(9)).contains("Eve");
        assertThat(session.rendered().slots().keySet()).containsExactlyInAnyOrder(9);
        assertThat(f.windows.log).contains("advance roster");
        assertThat(f.ran).containsExactly("mark:page 2 of 2");
    }

    @Test
    void anEntryOnTheSecondPageIsTheEntryItShows() {
        click(22);
        click(9);

        assertThat(f.ran).containsExactly("mark:page 2 of 2", "mark:picked Eve (slot 9)");
    }

    @Test
    void aClickOnAnEmptySlotRunsNothingAtAll() {
        click(22);
        int before = f.ran.size();

        ClickRouter.Routed routed = click(10);

        assertThat(routed.handled()).isFalse();
        assertThat(f.ran).hasSize(before);
    }

    @Test
    void aStepPastTheLastPageLeavesTheListOnItAndFiresNothing() {
        click(22);
        int before = f.ran.size();

        click(22);

        assertThat(session.pages()).containsEntry("players", Map.of("number", 2, "count", 2, "size", 4));
        assertThat(f.ran).hasSize(before);
    }

    @Test
    void theListIsClickableOnEverySlotItFills() {
        var definition = f.definition(ROSTER);
        var compiled = dev.materione.content.gui.GuiCompiler.compile(definition, 0xE200,
                dev.materione.api.gui.TextureSource.NONE).model();

        assertThat(compiled.lists().get(0).list().clickArea(dev.materione.api.gui.TextureSource.NONE))
                .isEqualTo(new ClickArea.Slots(java.util.Set.of(9, 10, 11)));
    }
}
