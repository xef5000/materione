package dev.materione.plugin.gui.tick;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.open.OpenRequest;
import dev.materione.plugin.gui.session.CloseReason;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code gui.tick} (logic.md §6.2): a per-session timer that exists only while the GUI is open and has
 * a tick rule, whose period is the gcd of the rules' {@code every:}.
 */
class GuiTickerTest {

    private static final String CLOCK = """
            clock:
              title: Clock
              rows: 1
              layers: []
              behavior:
                - on: gui.tick
                  every: 20
                  do:
                    - mark: "second at ${gui.id}"
                - on: gui.tick
                  every: 60
                  do:
                    - mark: minute-ish
            """;

    private GuiFixture f;

    @BeforeEach
    void setUp() {
        f = new GuiFixture();
    }

    private GuiSession open(String name) {
        return f.opener.open(OpenRequest.of(f.viewer, GuiFixture.id(name), Map.of(), OpenRequest.Cause.API)).session();
    }

    @Test
    void theRulesRunWhenTheirEveryDividesTheElapsedTicksAndTheTimerWakesOnTheGcd() {
        f.gui(CLOCK);
        open("clock");

        assertThat(f.ticks.periods).as("one timer, waking every 20 ticks (gcd of 20 and 60)").containsExactly(20L);

        f.ticks.advance(19);
        assertThat(f.ran).isEmpty();
        f.ticks.advance(1);
        assertThat(f.ran).containsExactly("mark:second at demo:clock");
        f.ticks.advance(40);
        assertThat(f.ran).containsExactly(
                "mark:second at demo:clock", "mark:second at demo:clock", "mark:second at demo:clock",
                "mark:minute-ish");
    }

    @Test
    void aGuiWithNoTickRuleStartsNoTimer() {
        f.gui("""
                quiet:
                  title: Quiet
                  rows: 1
                  layers: []
                """);

        open("quiet");

        assertThat(f.ticks.periods).isEmpty();
        assertThat(f.ticks.pending()).isZero();
    }

    @Test
    void closingStopsTheChainWithoutAnyCancel() {
        f.gui(CLOCK);
        GuiSession session = open("clock");
        f.ticks.advance(20);
        f.ran.clear();

        f.lifecycle.closeByAction(f.viewer, session);
        f.ticks.advance(100);

        assertThat(f.ran).as("nothing ticks after the close").isEmpty();
        assertThat(f.ticks.pending()).isZero();
    }

    @Test
    void replacingTheGuiEndsItsTimerAndTheNewGuisStartsItsOwn() {
        f.gui(CLOCK);
        f.gui("""
                other:
                  title: Other
                  rows: 1
                  layers: []
                  behavior:
                    - on: gui.tick
                      every: 5
                      do:
                        - mark: other-tick
                """);
        open("clock");
        open("other");
        f.ran.clear();

        f.ticks.advance(10);

        assertThat(f.ran).containsExactly("mark:other-tick", "mark:other-tick");
    }

    @Test
    void ensureIsIdempotentWhileATimerRuns() {
        f.gui(CLOCK);
        GuiSession session = open("clock");

        f.ticker.ensure(f.viewer, session);
        f.ticker.ensure(f.viewer, session);

        assertThat(f.ticks.periods).hasSize(1);
    }

    @Test
    void aReloadThatAddsTheFirstTickRuleStartsTheTimerForAnOpenSession() {
        f.gui("""
                clock:
                  title: Clock
                  rows: 1
                  layers: []
                """);
        GuiSession session = open("clock");
        assertThat(f.ticks.periods).isEmpty();

        f.gui(CLOCK); // the author adds tick rules; the session is the same one
        f.ticker.ensure(f.viewer, session);

        assertThat(f.ticks.periods).containsExactly(20L);
        f.ticks.advance(20);
        assertThat(f.ran).containsExactly("mark:second at demo:clock");
    }

    @Test
    void aReloadThatRemovesTheTickRulesStopsTheChain() {
        f.gui(CLOCK);
        open("clock");
        f.ticks.advance(20);
        f.ran.clear();

        f.gui("""
                clock:
                  title: Clock
                  rows: 1
                  layers: []
                """);
        f.ticks.advance(100);

        assertThat(f.ran).isEmpty();
        assertThat(f.ticks.pending()).isZero();
    }

    @Test
    void anEndedSessionNeverStartsATimer() {
        f.gui(CLOCK);
        GuiSession session = open("clock");
        f.ticks.advance(1);
        f.sessions.end(session, CloseReason.PLAYER);
        GuiSession ended = session;

        f.ticker.ensure(f.viewer, ended);
        f.ticks.advance(200);

        assertThat(f.ran).isEmpty();
    }
}
