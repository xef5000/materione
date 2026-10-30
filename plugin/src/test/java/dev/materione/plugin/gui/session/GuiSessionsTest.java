package dev.materione.plugin.gui.session;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Sessions, the per-player registry and the bounded back stack (logic.md §3.1, §6). */
class GuiSessionsTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");
    private static final NamespacedId MAP = NamespacedId.parse("demo:map");

    private final GuiSessions sessions = new GuiSessions();
    private final UUID alex = UUID.randomUUID();

    private GuiSession session(UUID player, NamespacedId gui) {
        return new GuiSession(player, "Alex", gui, Map.of("category", "tools"));
    }

    @Test
    void aSessionHoldsItsArgumentsVariablesAndPageStateAndStartsOpen() {
        GuiSession session = session(alex, SHOP);
        session.vars().put("tab", "weapons");
        session.pages().put("players", 2);

        assertThat(session.isOpen()).isTrue();
        assertThat(session.gui()).isEqualTo(SHOP);
        assertThat(session.args()).containsEntry("category", "tools");
        assertThat(session.vars()).containsEntry("tab", "weapons");
        assertThat(session.pages()).containsEntry("players", 2);
        assertThat(session.endedBecause()).isNull();
    }

    @Test
    void theArgumentsAreReadOnly() {
        GuiSession session = session(alex, SHOP);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> session.args().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSessionEndsOnceAndTheFirstReasonStands() {
        GuiSession session = session(alex, SHOP);

        assertThat(session.end(CloseReason.PLAYER)).isTrue();
        assertThat(session.end(CloseReason.ACTION)).isFalse();
        assertThat(session.endedBecause()).isEqualTo(CloseReason.PLAYER);
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void beginMakesTheSessionCurrentAndReportsWhatItDisplaced() {
        GuiSession first = session(alex, SHOP);
        GuiSession second = session(alex, MAP);

        assertThat(sessions.begin(first)).isEmpty();
        assertThat(sessions.current(alex)).containsSame(first);
        assertThat(sessions.begin(second)).containsSame(first);
        assertThat(sessions.current(alex)).containsSame(second);
    }

    @Test
    void sessionsAreIndependentPerPlayer() {
        UUID blake = UUID.randomUUID();
        GuiSession a = session(alex, SHOP);
        GuiSession b = session(blake, SHOP);
        sessions.begin(a);
        sessions.begin(b);

        sessions.end(a, CloseReason.PLAYER);

        assertThat(sessions.current(alex)).isEmpty();
        assertThat(sessions.current(blake)).containsSame(b);
        assertThat(sessions.showing(SHOP)).containsExactly(b);
        assertThat(sessions.openCount()).isEqualTo(1);
    }

    @Test
    void endingIsIdempotentAndAStaleSessionNeverEvictsTheCurrentOne() {
        GuiSession first = session(alex, SHOP);
        GuiSession second = session(alex, MAP);
        sessions.begin(first);
        sessions.begin(second);

        assertThat(sessions.end(first, CloseReason.REPLACED)).isTrue();
        assertThat(sessions.end(first, CloseReason.PLAYER)).isFalse();
        assertThat(sessions.current(alex)).containsSame(second);
    }

    @Test
    void aReplacedWindowKeepsTheBackStackAndAnyOtherEndDropsIt() {
        sessions.history(alex).push(session(alex, SHOP).snapshot());
        GuiSession map = session(alex, MAP);
        sessions.begin(map);

        sessions.end(map, CloseReason.REPLACED);
        assertThat(sessions.history(alex).size()).as("replaced keeps it").isEqualTo(1);

        GuiSession again = session(alex, MAP);
        sessions.begin(again);
        sessions.end(again, CloseReason.PLAYER);
        assertThat(sessions.history(alex).size()).as("a close drops it").isZero();
    }

    @Test
    void forgettingAPlayerDropsEverythingAboutThem() {
        sessions.begin(session(alex, SHOP));
        sessions.history(alex).push(session(alex, MAP).snapshot());

        sessions.forget(alex);

        assertThat(sessions.current(alex)).isEmpty();
        assertThat(sessions.history(alex).size()).isZero();
    }

    @Test
    void theBackStackPopsMostRecentFirstAndIsBounded() {
        var history = new SessionHistory();
        for (int i = 0; i < SessionHistory.MAX_DEPTH + 5; i++) {
            history.push(new GuiSession(alex, "Alex", SHOP, Map.of("n", Integer.toString(i))).snapshot());
        }

        assertThat(history.size()).isEqualTo(SessionHistory.MAX_DEPTH);
        assertThat(history.pop().orElseThrow().args()).containsEntry("n", Integer.toString(SessionHistory.MAX_DEPTH + 4));
        // the oldest entries were the ones dropped
        int last = 0;
        while (history.size() > 0) {
            last = Integer.parseInt(history.pop().orElseThrow().args().get("n"));
        }
        assertThat(last).isEqualTo(5);
        assertThat(history.pop()).isEmpty();
    }

    @Test
    void aSnapshotRestoresArgumentsVariablesAndPagesIntoAFreshSession() {
        GuiSession original = session(alex, SHOP);
        original.vars().put("tab", "weapons");
        original.pages().put("list", 3);
        GuiSession.Snapshot snapshot = original.snapshot();
        original.vars().put("tab", "armour"); // later changes do not reach the snapshot

        GuiSession back = new GuiSession(alex, "Alex", snapshot.gui(), snapshot.args());
        back.restore(snapshot);

        assertThat(back.args()).isEqualTo(original.args());
        assertThat(back.vars()).containsEntry("tab", "weapons");
        assertThat(back.pages()).containsEntry("list", 3);
        assertThat(back).isNotSameAs(original);
    }

    @Test
    void theTickerClaimIsExclusive() {
        GuiSession session = session(alex, SHOP);

        assertThat(session.claimTicker()).isTrue();
        assertThat(session.claimTicker()).isFalse();
        session.releaseTicker();
        assertThat(session.claimTicker()).isTrue();
    }

    @Test
    void closeReasonsKnowWhichFireAndWhichKeepHistory() {
        assertThat(CloseReason.REMOVED.fires()).isFalse();
        assertThat(CloseReason.PLAYER.fires()).isTrue();
        assertThat(CloseReason.REPLACED.keepsHistory()).isTrue();
        assertThat(CloseReason.ACTION.keepsHistory()).isFalse();
        assertThat(CloseReason.QUIT.id()).isEqualTo("quit");
    }
}
