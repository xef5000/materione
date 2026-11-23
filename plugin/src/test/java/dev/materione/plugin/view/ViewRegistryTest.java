package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.materione.plugin.gui.session.GuiViewerState;

/** Sessions, the per-player registry and the bounded back stack (logic.md §3.1, §6). */
class ViewRegistryTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");
    private static final NamespacedId MAP = NamespacedId.parse("demo:map");

    private final ViewRegistry sessions = new ViewRegistry();
    private final UUID alex = UUID.randomUUID();

    private ViewerRecord session(UUID player, NamespacedId gui) {
        return GuiViewerState.record(player, "Alex", gui, Map.of("category", "tools"));
    }

    @Test
    void aSessionHoldsItsArgumentsVariablesAndPageStateAndStartsOpen() {
        ViewerRecord session = session(alex, SHOP);
        session.sessionValue("tab", "weapons");
        GuiViewerState.of(session).putPage("players", 2);

        assertThat(session.isOpen()).isTrue();
        assertThat(session.definition()).isEqualTo(SHOP);
        assertThat(session.args()).containsEntry("category", "tools");
        assertThat(session.sessionValues()).containsEntry("tab", "weapons");
        assertThat(GuiViewerState.of(session).pages()).containsEntry("players", 2);
        assertThat(session.endedBecause()).isNull();
    }

    @Test
    void theArgumentsAreReadOnly() {
        ViewerRecord session = session(alex, SHOP);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> session.args().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aSessionEndsOnceAndTheFirstReasonStands() {
        ViewerRecord session = session(alex, SHOP);

        assertThat(session.end(CloseReason.PLAYER)).isTrue();
        assertThat(session.end(CloseReason.ACTION)).isFalse();
        assertThat(session.endedBecause()).isEqualTo(CloseReason.PLAYER);
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    void beginMakesTheSessionCurrentAndReportsWhatItDisplaced() {
        ViewerRecord first = session(alex, SHOP);
        ViewerRecord second = session(alex, MAP);

        assertThat(sessions.begin(first)).isEmpty();
        assertThat(sessions.current(alex)).containsSame(first);
        assertThat(sessions.begin(second)).containsSame(first);
        assertThat(sessions.current(alex)).containsSame(second);
    }

    @Test
    void sessionsAreIndependentPerPlayer() {
        UUID blake = UUID.randomUUID();
        ViewerRecord a = session(alex, SHOP);
        ViewerRecord b = session(blake, SHOP);
        sessions.begin(a);
        sessions.begin(b);

        sessions.end(a, CloseReason.PLAYER);

        assertThat(sessions.current(alex)).isEmpty();
        assertThat(sessions.current(blake)).containsSame(b);
        assertThat(sessions.showing(GuiViewerState.KIND, SHOP)).containsExactly(b);
        assertThat(sessions.openCount()).isEqualTo(1);
    }

    @Test
    void endingIsIdempotentAndAStaleSessionNeverEvictsTheCurrentOne() {
        ViewerRecord first = session(alex, SHOP);
        ViewerRecord second = session(alex, MAP);
        sessions.begin(first);
        sessions.begin(second);

        assertThat(sessions.end(first, CloseReason.REPLACED)).isTrue();
        assertThat(sessions.end(first, CloseReason.PLAYER)).isFalse();
        assertThat(sessions.current(alex)).containsSame(second);
    }

    @Test
    void aReplacedWindowKeepsTheBackStackAndAnyOtherEndDropsIt() {
        sessions.history(alex).push(session(alex, SHOP).snapshot());
        ViewerRecord map = session(alex, MAP);
        sessions.begin(map);

        sessions.end(map, CloseReason.REPLACED);
        assertThat(sessions.history(alex).size()).as("replaced keeps it").isEqualTo(1);

        ViewerRecord again = session(alex, MAP);
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
        var history = new ViewHistory();
        for (int i = 0; i < ViewHistory.MAX_DEPTH + 5; i++) {
            history.push(GuiViewerState.record(alex, "Alex", SHOP, Map.of("n", Integer.toString(i))).snapshot());
        }

        assertThat(history.size()).isEqualTo(ViewHistory.MAX_DEPTH);
        assertThat(history.pop().orElseThrow().args()).containsEntry("n", Integer.toString(ViewHistory.MAX_DEPTH + 4));
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
        ViewerRecord original = session(alex, SHOP);
        original.sessionValue("tab", "weapons");
        GuiViewerState.of(original).putPage("list", 3);
        ViewerRecord.Snapshot snapshot = original.snapshot();
        original.sessionValue("tab", "armour"); // later changes do not reach the snapshot

        ViewerRecord back = GuiViewerState.record(alex, "Alex", snapshot.definition(), snapshot.args());
        back.restore(snapshot);

        assertThat(back.args()).isEqualTo(original.args());
        assertThat(back.sessionValues()).containsEntry("tab", "weapons");
        assertThat(GuiViewerState.of(back).pages()).containsEntry("list", 3);
        assertThat(back).isNotSameAs(original);
    }

    @Test
    void theTickerClaimIsExclusive() {
        ViewerRecord session = session(alex, SHOP);

        assertThat(GuiViewerState.of(session).claimTicker()).isTrue();
        assertThat(GuiViewerState.of(session).claimTicker()).isFalse();
        GuiViewerState.of(session).releaseTicker();
        assertThat(GuiViewerState.of(session).claimTicker()).isTrue();
    }

    @Test
    void closeReasonsKnowWhichFireAndWhichKeepHistory() {
        assertThat(CloseReason.REMOVED.fires()).isFalse();
        assertThat(CloseReason.PLAYER.fires()).isTrue();
        assertThat(CloseReason.REPLACED.keepsHistory()).isTrue();
        assertThat(CloseReason.ACTION.keepsHistory()).isFalse();
        assertThat(CloseReason.QUIT.id()).isEqualTo("quit");
    }

    // --- the model of views.md section 3.8: any kind, one record each, indexed by player and definition ----------------

    private static ViewerRecord record(UUID player, String kind, NamespacedId definition) {
        return new ViewerRecord(player, "Alex", kind, definition, Map.of(), new ViewerPayload() { });
    }

    @Test
    void aPlayerHasOneRecordPerKindAndAKindDoesNotDisplaceAnother() {
        ViewerRecord gui = session(alex, SHOP);
        ViewerRecord dialog = record(alex, "dialog", MAP);
        sessions.begin(gui);

        assertThat(sessions.begin(dialog)).as("a dialog over a container displaces nothing").isEmpty();

        assertThat(sessions.current(alex, "gui")).containsSame(gui);
        assertThat(sessions.current(alex, "dialog")).containsSame(dialog);
        assertThat(sessions.current(alex)).as("the latest begun is the current one").containsSame(dialog);
        assertThat(sessions.openCount()).as("one player").isEqualTo(1);
        assertThat(sessions.all()).containsExactlyInAnyOrder(gui, dialog);
        assertThat(sessions.all("dialog")).containsExactly(dialog);

        ViewerRecord next = record(alex, "dialog", SHOP);
        assertThat(sessions.begin(next)).containsSame(dialog);
        assertThat(sessions.current(alex, "gui")).as("the GUI is untouched").containsSame(gui);
    }

    @Test
    void theHistoryAndTheLastViewSpanKinds() {
        ViewerRecord gui = session(alex, SHOP);
        ViewerRecord dialog = record(alex, "dialog", MAP);
        sessions.begin(gui);
        sessions.begin(dialog);
        sessions.history(alex).push(gui.snapshot());
        sessions.history(alex).push(dialog.snapshot());

        sessions.end(dialog, CloseReason.REPLACED);

        assertThat(sessions.last(alex)).hasValueSatisfying(last -> {
            assertThat(last.kind()).isEqualTo("dialog");
            assertThat(last.toString()).isEqualTo("dialog:demo:map");
        });
        assertThat(sessions.history(alex).pop()).hasValueSatisfying(
                snapshot -> assertThat(snapshot.kind()).isEqualTo("dialog"));
        assertThat(sessions.history(alex).pop()).hasValueSatisfying(
                snapshot -> assertThat(snapshot.kind()).isEqualTo("gui"));
        assertThat(sessions.current(alex)).as("the GUI is still current").containsSame(gui);
    }

    @Test
    void theHistorySurvivesUntilThePlayerHasNothingOpenAndAnyOtherEndDropsIt() {
        ViewerRecord gui = session(alex, SHOP);
        ViewerRecord dialog = record(alex, "dialog", MAP);
        sessions.begin(gui);
        sessions.begin(dialog);
        sessions.history(alex).push(gui.snapshot());

        sessions.end(dialog, CloseReason.ACTION);
        assertThat(sessions.history(alex).size()).as("the GUI is still open: back still has somewhere to go").isEqualTo(1);

        sessions.end(gui, CloseReason.ACTION);
        assertThat(sessions.history(alex).size()).isZero();
    }

    @Test
    void definitionsAreIndexedAndTheIndexFollowsTheRecords() {
        UUID blake = UUID.randomUUID();
        ViewerRecord first = session(alex, SHOP);
        ViewerRecord second = session(blake, SHOP);
        sessions.begin(first);
        sessions.begin(second);
        sessions.begin(session(alex, MAP)); // alex leaves the shop for the map

        assertThat(sessions.showing(GuiViewerState.KIND, SHOP)).containsExactly(second);
        assertThat(sessions.showing(GuiViewerState.KIND, MAP)).hasSize(1);

        sessions.end(second, CloseReason.PLAYER);
        assertThat(sessions.showing(GuiViewerState.KIND, SHOP)).isEmpty();

        sessions.forget(alex);
        assertThat(sessions.showing(GuiViewerState.KIND, MAP)).isEmpty();
        assertThat(sessions.all()).isEmpty();
    }

    @Test
    void aGuiAndADialogSharingAnIdAreIndexedApart() {
        ViewerRecord gui = session(alex, SHOP);
        ViewerRecord dialog = record(alex, "dialog", SHOP);
        sessions.begin(gui);
        sessions.begin(dialog);

        assertThat(sessions.showing(GuiViewerState.KIND, SHOP)).containsExactly(gui);
        assertThat(sessions.showing("dialog", SHOP)).containsExactly(dialog);

        sessions.end(dialog, CloseReason.PLAYER);
        assertThat(sessions.showing(GuiViewerState.KIND, SHOP)).containsExactly(gui);
        assertThat(sessions.showing("dialog", SHOP)).isEmpty();
    }

    @Test
    void theKeyIndexIsReservedForRowV5() {
        sessions.begin(session(alex, SHOP));

        assertThat(sessions.showingKey("trade-1")).as("no key binding exists yet").isEmpty();
    }
}
