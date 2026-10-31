package dev.materione.plugin.gui.open;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** How a session ends (logic.md §6.2): each reason fires {@code gui.close} once, with its reason. */
class GuiLifecycleTest {

    private static final String SHOP = """
            shop:
              title: Shop
              rows: 3
              layers: []
              behavior:
                - on: gui.close
                  do:
                    - mark: "any ${gui.id} ${close.reason}"
                - on: gui.close
                  reason: player
                  do:
                    - mark: by-player
                - on: gui.close
                  reason: action
                  do:
                    - mark: by-action
                - on: gui.close
                  reason: quit
                  do:
                    - mark: by-quit
            """;

    private GuiFixture f;
    private GuiSession session;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
        f.gui(SHOP);
        session = f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id("shop"), Map.of(), OpenRequest.Cause.API))
                .session();
        f.ran.clear();
    }

    @Test
    void theWindowClosingFiresThePlayerReason() {
        f.lifecycle.windowClosed(f.viewer, session, CloseReason.PLAYER);

        assertThat(f.ran).containsExactly("mark:any demo:shop player", "mark:by-player");
        assertThat(session.endedBecause()).isEqualTo(CloseReason.PLAYER);
        assertThat(f.sessions.current(f.viewer.id())).isEmpty();
        assertThat(f.windows.log).as("the window already closed itself").containsExactly("open shop");
    }

    @Test
    void aFlowClosingTheWindowFiresTheActionReasonAndClosesIt() {
        assertThat(f.lifecycle.closeByAction(f.viewer, session)).isTrue();

        assertThat(f.ran).containsExactly("mark:any demo:shop action", "mark:by-action");
        assertThat(f.windows.log).containsExactly("open shop", "close");
    }

    @Test
    void leavingTheServerFiresTheQuitReasonAndForgetsThePlayer() {
        f.sessions.history(f.viewer.id()).push(session.snapshot());

        f.lifecycle.playerQuit(f.viewer);

        assertThat(f.ran).containsExactly("mark:any demo:shop quit", "mark:by-quit");
        assertThat(f.sessions.current(f.viewer.id())).isEmpty();
        assertThat(f.sessions.history(f.viewer.id()).size()).isZero();
    }

    @Test
    void aSessionThatEndedAlreadyFiresNothingMore() {
        f.lifecycle.windowClosed(f.viewer, session, CloseReason.QUIT);
        f.ran.clear();

        // the disconnect closes the window (quit) and then the quit event arrives
        f.lifecycle.playerQuit(f.viewer);
        assertThat(f.lifecycle.closeByAction(f.viewer, session)).isFalse();
        f.lifecycle.windowClosed(f.viewer, session, CloseReason.PLAYER);

        assertThat(f.ran).isEmpty();
        assertThat(session.endedBecause()).isEqualTo(CloseReason.QUIT);
    }

    @Test
    void aGuiThatWentAwayEndsItsSessionQuietly() {
        f.lifecycle.removed(f.viewer, session);

        assertThat(session.endedBecause()).isEqualTo(CloseReason.REMOVED);
        assertThat(f.ran).isEmpty();
        assertThat(f.windows.released).containsExactly(session);
    }
}
