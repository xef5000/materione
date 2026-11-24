package dev.materione.plugin.gui.live;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.GuiReads;
import dev.materione.plugin.gui.render.GuiRenderer;
import dev.materione.plugin.gui.render.RenderReporter;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.ReadsIndex;
import dev.materione.plugin.view.ViewChanges;
import dev.materione.plugin.view.ViewScheduler;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.plugin.view.open.OpenRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Render on change for a container GUI (views.md sections 4.2 and 5.5, issue exit criteria): a GUI that shows a server
 * variable or a window-local one updates after a write with no {@code refresh:} clock and no explicit refresh, a GUI that
 * says {@code rerender: off} does not, and a window held still by the cursor shows the latest state after the release.
 */
class RerenderOnChangeTest {

    private static final String SHOWS_SEASON = """
            menu:
              title: Menu
              container: chest_3
              %s
              layers:
                - id: summer
                  type: item
                  slot: 4
                  item: missing:sun
                  visible_if: "vars.server.demo.season == 'summer'"
            """;

    private GuiFixture f;
    private final AtomicLong tick = new AtomicLong(10);
    private final List<Runnable> queue = new ArrayList<>();
    private ViewScheduler scheduler;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.variables.declare("demo", "season", VariableScope.SERVER, "spring");
        f.variables.declare("demo", "tab", VariableScope.SESSION, "all");
        f.windows.renderer = new GuiRenderer(RenderReporter.SILENT, f.variables.service());
    }

    /** Publishes the GUI and wires the observer to a scheduler on a virtual queue, as the plugin does after a reload. */
    private ViewerRecord open(String yaml) {
        f.gui(yaml);
        return openPublished(GuiFixture.id("menu"));
    }

    private ViewerRecord openPublished(NamespacedId gui) {
        var index = ReadsIndex.of(GuiReads.of(f.view(), f.logic.lookup(), f.rerenders()));
        scheduler = new ViewScheduler(f.sessions, f.kinds, tick::get,
                (player, delay, task, retired) -> queue.add(task), id -> f.viewer, ViewScheduler.RenderCounter.NONE);
        f.variables.service().writeObserver(new ViewChanges(() -> index, f.sessions, f.kinds, scheduler));
        ViewerRecord record = f.opener.open(OpenRequest.of(f.viewer, gui, Map.of(),
                OpenRequest.Cause.API)).record();
        f.windows.log.clear();
        return record;
    }

    private void runQueue() {
        tick.incrementAndGet();
        List<Runnable> due = List.copyOf(queue);
        queue.clear();
        due.forEach(Runnable::run);
    }

    private boolean summerShown(ViewerRecord record) {
        return GuiViewerState.of(record).rendered().slots().containsKey(4);
    }

    private void setSeason(String value) {
        var season = f.variables.service().declaration(NamespacedId.of("demo", "season")).orElseThrow();
        assertThat(f.variables.service().store(VariableScope.SERVER).write(season, null, value)).isTrue();
    }

    @Test
    void aServerVariableWriteUpdatesTheWindowWithNoClockAndNoRefresh() {
        ViewerRecord record = open(SHOWS_SEASON.formatted(""));
        assertThat(summerShown(record)).isFalse();

        setSeason("summer");
        assertThat(f.windows.log).as("never inline in the write").isEmpty();
        runQueue();

        assertThat(f.windows.log).containsExactly("advance menu");
        assertThat(summerShown(record)).isTrue();
        assertThat(f.ticks.pending()).as("no clock was started for it").isZero();
    }

    @Test
    void rerenderOffLeavesTheWindowUntilAClockAnExplicitRefreshOrAReload() {
        ViewerRecord record = open(SHOWS_SEASON.formatted("rerender: off"));

        setSeason("summer");
        runQueue();

        assertThat(f.windows.log).isEmpty();
        assertThat(summerShown(record)).isFalse();
        assertThat(scheduler.refreshNow(record)).isTrue();
        assertThat(summerShown(record)).isTrue();
    }

    @Test
    void aWindowLocalVariableWriteRendersThatWindow() {
        ViewerRecord record = open("""
                menu:
                  title: Menu
                  container: chest_3
                  layers:
                    - id: tools
                      type: item
                      slot: 4
                      item: missing:pick
                      visible_if: "vars.session.demo.tab == 'tools'"
                """);
        assertThat(summerShown(record)).isFalse();

        f.putSession(record, "tab", "tools");
        runQueue();

        assertThat(f.windows.log).containsExactly("advance menu");
        assertThat(summerShown(record)).isTrue();
    }

    @Test
    void theGuilogicLiveFixtureRendersOnAServerWriteAndOnItsOwnToggleWithNoClock() throws Exception {
        f = new GuiFixture("guilogic", dev.materione.plugin.logic.support.ItemCatalog.none());
        f.variables.declare("guilogic", "wave", VariableScope.SERVER, "calm");
        f.variables.declare("guilogic", "shown", VariableScope.SESSION, false);
        f.windows.renderer = new GuiRenderer(RenderReporter.SILENT, f.variables.service());
        String yaml = java.nio.file.Files.readString(dev.materione.testing.golden.GoldenFiles.fixturesRoot()
                .resolve("packs").resolve("guilogic").resolve("guis").resolve("live.yml"));
        var live = f.gui(yaml.replace("minecraft:", "missing:")); // a vanilla material needs a server to resolve
        assertThat(f.diagnostics).isEmpty();
        assertThat(live.clockPeriod()).as("no refresh clock").isZero();
        ViewerRecord record = openPublished(NamespacedId.of("guilogic", "live"));

        var wave = f.variables.service().declaration(NamespacedId.of("guilogic", "wave")).orElseThrow();
        f.variables.service().store(VariableScope.SERVER).write(wave, null, "storm");
        f.variables.put(record, f.variables.service().declaration(NamespacedId.of("guilogic", "shown")).orElseThrow(), true);
        assertThat(f.windows.log).isEmpty();
        runQueue();

        assertThat(f.windows.log).as("one render for both writes").containsExactly("advance live");
        assertThat(GuiViewerState.of(record).rendered().slots()).containsKey(13);
    }

    @Test
    void aHeldCursorLeavesTheWriteWaitingAndTheLatestStateRendersAfterTheRelease() {
        ViewerRecord record = open(SHOWS_SEASON.formatted(""));
        f.windows.held = true;

        setSeason("summer");
        runQueue();
        runQueue();

        assertThat(summerShown(record)).isFalse();
        assertThat(record.isDirty()).isTrue();

        setSeason("winter");
        setSeason("summer");
        f.windows.held = false;
        runQueue();

        assertThat(summerShown(record)).isTrue();
        assertThat(record.isDirty()).isFalse();
    }
}
