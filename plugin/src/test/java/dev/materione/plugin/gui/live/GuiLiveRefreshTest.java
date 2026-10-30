package dev.materione.plugin.gui.live;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.RuntimeGuis;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live refresh keeps the session (gui.md §5, logic.md §6): when a reload changes a GUI, its viewers get
 * the new window and their session, arguments, variables and back stack carry on; a GUI that is gone
 * ends its sessions.
 */
class GuiLiveRefreshTest {

    private static String shop(String title, int rows) {
        return """
                shop:
                  title: %s
                  rows: %d
                  layers:
                    - id: heading
                      type: text
                      text: "%s"
                      x: 8
                      y: 6
                      font: smallcaps
                """.formatted(title, rows, title);
    }

    private GuiFixture f;
    private GuiSession session;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.gui(shop("Shop", 3));
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of("category", "tools"),
                OpenRequest.Cause.API)).session();
        session.vars().put("tab", "weapons");
        session.pages().put("list", 4);
        f.windows.log.clear();
    }

    private void reloadWith(String yaml) {
        f.gui(yaml);
    }

    private Optional<RuntimeGuis.Live> now() {
        return f.guis.find(GuiFixture.id("shop"));
    }

    private void assertSessionSurvived() {
        assertThat(session.isOpen()).isTrue();
        assertThat(f.sessions.current(f.viewer.id())).containsSame(session);
        assertThat(session.args()).containsEntry("category", "tools");
        assertThat(session.vars()).containsEntry("tab", "weapons");
        assertThat(session.pages()).containsEntry("list", 4);
    }

    @Test
    void aChangeToTheItemsRewritesTheWindowInPlaceAndTheSessionSurvives() {
        // same title and rows: the host rewrites the slots of the open window
        f.refresh.refreshViewer(f.viewer, session, now());

        assertThat(f.windows.log).containsExactly("rewrite shop");
        assertSessionSurvived();
    }

    @Test
    void aNewTitleOrSizeReopensTheWindowForTheSameSession() {
        reloadWith(shop("Market", 3));

        f.refresh.refreshViewer(f.viewer, session, now());

        assertThat(f.windows.log).containsExactly("reopen shop");
        assertSessionSurvived();

        reloadWith(shop("Market", 4));
        f.refresh.refreshViewer(f.viewer, session, now());
        assertThat(f.windows.log).containsExactly("reopen shop", "reopen shop");
        assertSessionSurvived();
    }

    @Test
    void aGuiThatIsGoneEndsItsSessionClosesTheWindowAndTellsTheViewer() {
        f.sessions.history(f.viewer.id()).push(session.snapshot());
        f.remove("shop");

        f.refresh.refreshViewer(f.viewer, session, now());

        assertThat(session.endedBecause()).isEqualTo(CloseReason.REMOVED);
        assertThat(f.sessions.current(f.viewer.id())).isEmpty();
        assertThat(f.sessions.history(f.viewer.id()).size()).isZero();
        assertThat(f.windows.log).containsExactly("close");
        assertThat(f.messages).anyMatch(message -> message.contains("no longer available"));
    }

    @Test
    void aSessionWhoseWindowIsNotShowingIsLeftAlone() {
        f.windows.shapes.clear(); // the player opened something else on top

        f.refresh.refreshViewer(f.viewer, session, now());

        assertThat(f.windows.log).isEmpty();
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    void guiRefreshRewritesInPlaceAndSaysWhenItCannot() {
        assertThat(f.refresh.refresh(f.viewer, session)).isTrue();
        assertThat(f.windows.log).containsExactly("rewrite shop");

        f.remove("shop");
        assertThat(f.refresh.refresh(f.viewer, session)).isFalse();
    }

    @Test
    void theRulesOfTheReloadedGuiAreTheOnesTheNextClickRuns() {
        f.gui("""
                shop:
                  title: Shop
                  rows: 3
                  layers:
                    - id: pad
                      type: button
                      x: 8
                      y: 18
                      width: 16
                      height: 16
                  behavior:
                    - on: gui.click
                      layer: pad
                      do:
                        - mark: "v1 ${gui.args.category} ${gui.session.tab}"
                """);
        f.router.route(new dev.materione.plugin.gui.input.GuiClick(f.viewer, session, 0,
                dev.materione.plugin.gui.input.ClickKind.LEFT, 0));
        assertThat(f.ran).containsExactly("mark:v1 tools weapons");

        f.gui("""
                shop:
                  title: Shop
                  rows: 3
                  layers:
                    - id: pad
                      type: button
                      x: 8
                      y: 18
                      width: 16
                      height: 16
                  behavior:
                    - on: gui.click
                      layer: pad
                      do:
                        - mark: "v2 ${gui.args.category} ${gui.session.tab}"
                """);
        f.router.route(new dev.materione.plugin.gui.input.GuiClick(f.viewer, session, 0,
                dev.materione.plugin.gui.input.ClickKind.LEFT, 0));

        assertThat(f.ran).containsExactly("mark:v1 tools weapons", "mark:v2 tools weapons");
        assertSessionSurvived();
    }
}
