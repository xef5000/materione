package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@code view.refresh}'s selection and the semantics a dialog kind needs from the kind contract (views.md sections 3.6,
 * 4.3, 5.4), proved with a test kind that behaves as a dialog does: an explicit render sends a new token, an automatic
 * one compares what it would send with what the viewer holds and, when nothing differs, sends nothing and keeps the token.
 */
class ViewRefresherTest {

    private static final NamespacedId ASK = NamespacedId.parse("demo:ask");
    private static final NamespacedId VOTE = NamespacedId.parse("demo:vote");

    private record Task(long due, long order, Runnable run) {}

    /** What a dialog viewer holds: the form it was sent and the token that answers it. */
    private static final class Sent implements ViewerPayload {
        String form;
        int token;
    }

    /** The test dialog: its form is the value of one player variable, read when it renders. */
    private final class DialogKind implements ViewKind {

        int sends;
        int tokens;

        @Override
        public String contentType() {
            return "dialog";
        }

        @Override
        public ViewFacet facet() {
            return new ViewFacet(ViewFacet.ViewerRule.OPENED, ViewFacet.RerenderPolicy.OFF, false, false);
        }

        @Override
        public int minInterval() {
            return 20;
        }

        @Override
        public Optional<NamespacedId> resolve(String written, NamespacedId near) {
            return Optional.empty();
        }

        @Override
        public OpenResult open(OpenRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean close(Viewer viewer, ViewerRecord record) {
            throw new UnsupportedOperationException();
        }

        private String form(ViewerRecord record) {
            return "tally " + variables.service().store(VariableScope.PLAYER).read(coins, record.player()).orElse(0d);
        }

        private void send(ViewerRecord record) {
            Sent held = (Sent) record.payload();
            held.form = form(record);
            held.token = ++tokens;
            sends++;
        }

        @Override
        public boolean render(Viewer viewer, ViewerRecord record) {
            send(record); // an explicit render re-sends whether or not anything changed: the author asked
            return true;
        }

        @Override
        public RenderOutcome renderAutomatic(Viewer viewer, ViewerRecord record) {
            if (form(record).equals(((Sent) record.payload()).form)) {
                return RenderOutcome.UNCHANGED;
            }
            send(record);
            return RenderOutcome.RENDERED;
        }
    }

    private final AtomicLong tick = new AtomicLong(100);
    private final List<Task> queue = new ArrayList<>();
    private long order;
    private boolean owner = true;
    private final ViewRegistry registry = new ViewRegistry();
    private final ViewKinds kinds = new ViewKinds();
    private final TestVariables variables = new TestVariables(registry);
    private final DialogKind dialog = new DialogKind();
    private final UUID alex = UUID.randomUUID();
    private final UUID blake = UUID.randomUUID();
    private final VariableDeclaration coins = variables.declare("demo", "coins", VariableScope.PLAYER, 0d);
    private final VariableDeclaration season = variables.declare("demo", "season", VariableScope.SERVER, 1d);
    private final ViewScheduler scheduler = new ViewScheduler(registry, kinds, tick::get,
            (player, delay, task, retired) -> queue.add(new Task(tick.get() + delay, order++, task)),
            id -> new Viewer(id, "p", null), ViewScheduler.RenderCounter.NONE);
    private volatile ReadsIndex index = ReadsIndex.empty();
    private final ViewRefresher refresher = new ViewRefresher(registry, () -> index, scheduler, id -> owner);

    ViewRefresherTest() {
        kinds.register(dialog);
        variables.service().writeObserver(new ViewChanges(() -> index, registry, kinds, scheduler));
    }

    private ViewerRecord open(UUID player, NamespacedId definition) {
        var record = new ViewerRecord(player, "p", "dialog", definition, Map.of(), new Sent());
        registry.begin(record);
        dialog.send(record);
        dialog.sends = 0;
        return record;
    }

    private static int tokenOf(ViewerRecord record) {
        return ((Sent) record.payload()).token;
    }

    /** Publishes an index in which {@code definition} reads {@code variable}: a dialog that says {@code on_change}. */
    private void reads(NamespacedId definition, VariableDeclaration variable) {
        index = ReadsIndex.of(Map.of(new ViewRef("dialog", definition),
                new ViewReads(Map.of(new ReadKey(variable.scope(), variable.id()), EnumSet.of(ReadRole.CONTENT)), false)));
    }

    private void write(UUID player, double value) {
        variables.service().store(VariableScope.PLAYER).write(coins, player, value);
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick.incrementAndGet();
            List<Task> due = queue.stream().filter(task -> task.due() <= tick.get())
                    .sorted(Comparator.comparingLong(Task::order)).toList();
            queue.removeAll(due);
            due.forEach(task -> task.run().run());
        }
    }

    @Test
    void anExplicitSelectionByDefinitionResendsEveryViewerEvenWhenNothingChanged() {
        ViewerRecord first = open(alex, ASK);
        ViewerRecord second = open(blake, ASK);
        ViewerRecord elsewhere = open(UUID.randomUUID(), VOTE);
        int before = tokenOf(first);

        assertThat(refresher.definition("dialog", ASK)).isEqualTo(2);

        assertThat(dialog.sends).isEqualTo(2);
        assertThat(tokenOf(first)).isGreaterThan(before);
        assertThat(tokenOf(second)).isGreaterThan(0);
        assertThat(tokenOf(elsewhere)).isEqualTo(3);
    }

    @Test
    void aVariableSelectionHonoursRerenderBecauseADialogThatSaysOffIsNotInTheIndex() {
        open(alex, ASK);

        assertThat(refresher.variable(coins, alex)).as("rerender: off, so nothing reads the variable").isZero();
        assertThat(dialog.sends).isZero();

        reads(ASK, coins); // the document says rerender: on_change
        assertThat(refresher.variable(coins, alex)).isEqualTo(1);
        assertThat(dialog.sends).as("a var: selection is explicit: the author asked").isEqualTo(1);
    }

    @Test
    void aVariableSelectionNarrowsByHolder() {
        ViewerRecord mine = open(alex, ASK);
        ViewerRecord theirs = open(blake, ASK);
        reads(ASK, coins);

        refresher.variable(coins, blake);

        assertThat(tokenOf(mine)).isEqualTo(1);
        assertThat(tokenOf(theirs)).isEqualTo(3);
    }

    @Test
    void aServerVariableSelectsEveryViewerOfTheReadingDefinition() {
        ViewerRecord a = open(alex, ASK);
        ViewerRecord b = open(blake, ASK);
        ViewerRecord other = open(UUID.randomUUID(), VOTE);
        reads(ASK, season);

        assertThat(refresher.variable(season, null)).isEqualTo(2);
        assertThat(tokenOf(a)).isGreaterThan(1);
        assertThat(tokenOf(b)).isGreaterThan(2);
        assertThat(tokenOf(other)).isEqualTo(3);
    }

    @Test
    void anUnchangedAutomaticWakeKeepsTheTokenAndSendsNothing() {
        reads(ASK, coins);
        ViewerRecord record = open(alex, ASK);
        int token = tokenOf(record);

        write(alex, 0d); // a write the form does not show: the same tally
        ticks(1);

        assertThat(dialog.sends).isZero();
        assertThat(tokenOf(record)).isEqualTo(token);
        assertThat(record.isDirty()).as("the wake cleared the bit").isFalse();
    }

    @Test
    void aChangedAutomaticWakeResendsWithANewToken() {
        reads(ASK, coins);
        ViewerRecord record = open(alex, ASK);
        int token = tokenOf(record);

        write(alex, 5d);
        ticks(1);

        assertThat(dialog.sends).isEqualTo(1);
        assertThat(tokenOf(record)).isGreaterThan(token);
    }

    @Test
    void anUnchangedWakeDoesNotStartTheIntervalSoTheNextChangeIsNotHeldBack() {
        reads(ASK, coins);
        open(alex, ASK);

        write(alex, 0d);
        ticks(1);
        write(alex, 7d);
        ticks(1);

        assertThat(dialog.sends).as("the changed write rendered at the next tick, not after 20").isEqualTo(1);
    }

    @Test
    void anExplicitSelectionIgnoresTheIntervalAndLeavesAnAutomaticWakeToDoNothing() {
        reads(ASK, coins);
        ViewerRecord record = open(alex, ASK);
        write(alex, 3d);
        ticks(1);
        assertThat(dialog.sends).isEqualTo(1);

        write(alex, 4d); // inside the 20-tick floor: the wake is queued for later
        refresher.definition("dialog", ASK);
        assertThat(dialog.sends).as("sent at once, floor or not").isEqualTo(2);

        ticks(25);
        assertThat(dialog.sends).as("the queued wake found the bit clean").isEqualTo(2);
        assertThat(tokenOf(record)).isEqualTo(3);
    }

    @Test
    void fromAnotherThreadEachViewerGetsAnImmediateWakeOnItsOwn() {
        ViewerRecord record = open(alex, ASK);
        owner = false;

        refresher.definition("dialog", ASK);
        assertThat(dialog.sends).as("not on the viewer's thread: nothing at once").isZero();

        ticks(1);
        assertThat(dialog.sends).isEqualTo(1);
        assertThat(tokenOf(record)).isEqualTo(2);
    }

    @Test
    void aSessionVariableSelectsTheOneRecordHoldingTheInstance() {
        VariableDeclaration tab = variables.declare("demo", "tab", VariableScope.SESSION, "a");
        ViewerRecord mine = open(alex, ASK);
        open(blake, ASK);
        index = ReadsIndex.of(Map.of(new ViewRef("dialog", ASK),
                new ViewReads(Map.of(new ReadKey(tab.scope(), tab.id()), EnumSet.of(ReadRole.CONTENT)), false)));

        assertThat(refresher.variable(tab, mine.instance())).isEqualTo(1);
        assertThat(dialog.sends).isEqualTo(1);
    }

    @Test
    void aSelectionBeforeAnyContentIsAppliedIsEmpty() {
        var empty = new ViewRefresher(registry, () -> null, scheduler, id -> true);
        open(alex, ASK);

        assertThat(empty.variable(coins, alex)).isZero();
        assertThat(dialog.sends).isZero();
    }
}
