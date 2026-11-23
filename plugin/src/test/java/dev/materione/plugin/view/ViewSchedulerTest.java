package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The scheduler (views.md sections 5.4 and 5.7, acceptance cases 1 and 3) on a virtual clock: a write becomes at most
 * one automatic render per viewer per tick, an idle viewer costs nothing, the interval and the stale-record rules hold,
 * and an explicit refresh and a queued wake render once between them.
 */
class ViewSchedulerTest {

    private static final NamespacedId MENU = NamespacedId.parse("demo:menu");
    private static final NamespacedId OTHER = NamespacedId.parse("demo:other");
    private static final NamespacedId BAR = NamespacedId.parse("rpg:bar");

    /** A queued one-shot of the virtual gateway. */
    private record Task(long due, long order, Runnable run) {}

    /** The kind under test: counts what the scheduler asks of it. */
    private final class Kind implements ViewKind {
        final String type;
        final boolean predicate;
        int interval = 1;
        final AtomicInteger renders = new AtomicInteger();
        final AtomicInteger evaluations = new AtomicInteger();
        ViewKind.RenderOutcome outcome = ViewKind.RenderOutcome.RENDERED;
        Runnable duringRender = () -> { };
        final List<ViewerRecord> rendered = new ArrayList<>();

        Kind(String type, boolean predicate) {
            this.type = type;
            this.predicate = predicate;
        }

        @Override
        public String contentType() {
            return type;
        }

        @Override
        public ViewFacet facet() {
            return new ViewFacet(predicate ? ViewFacet.ViewerRule.PREDICATE : ViewFacet.ViewerRule.OPENED,
                    ViewFacet.RerenderPolicy.ON_CHANGE, false, false);
        }

        @Override
        public int minInterval() {
            return interval;
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

        @Override
        public boolean render(Viewer viewer, ViewerRecord record) {
            renders.incrementAndGet();
            rendered.add(record);
            duringRender.run();
            return true;
        }

        @Override
        public RenderOutcome renderAutomatic(Viewer viewer, ViewerRecord record) {
            if (outcome == RenderOutcome.RENDERED) {
                render(viewer, record);
            }
            return outcome;
        }

        @Override
        public void evaluate(Viewer viewer, ViewCandidate candidate) {
            evaluations.incrementAndGet();
            if (variables.service().store(VariableScope.PLAYER).read(hudOn, candidate.player()).orElse(false)
                    .equals(true) && registry.current(candidate.player(), type).isEmpty()) {
                registry.begin(GuiViewerState.record(candidate.player(), "p", BAR, Map.of()));
            }
        }
    }

    private final AtomicLong tick = new AtomicLong(100);
    private final List<Task> queue = new ArrayList<>();
    private long order;
    private final ViewRegistry registry = new ViewRegistry();
    private final ViewKinds kinds = new ViewKinds();
    private final TestVariables variables = new TestVariables(registry);
    private final Kind gui = new Kind("gui", false);
    private final Kind hud = new Kind("hud", true);
    private final UUID alex = UUID.randomUUID();
    private final UUID blake = UUID.randomUUID();
    private final VariableDeclaration coins = variables.declare("demo", "coins", VariableScope.PLAYER, 0d);
    private final VariableDeclaration season = variables.declare("demo", "season", VariableScope.SERVER, 1d);
    private final VariableDeclaration hudOn = variables.declare("rpg", "hud_on", VariableScope.PLAYER, false);
    private final ViewScheduler scheduler = new ViewScheduler(registry, kinds, tick::get,
            (player, delay, task, retired) -> queue.add(new Task(tick.get() + delay, order++, task)),
            id -> new Viewer(id, "p", null), ViewScheduler.RenderCounter.NONE);
    private final ReadsIndexHolder index = new ReadsIndexHolder();

    /** The live index, replaced per test. */
    private static final class ReadsIndexHolder {
        volatile ReadsIndex current = ReadsIndex.empty();
    }

    ViewSchedulerTest() {
        kinds.register(gui);
        kinds.register(hud);
        variables.service().writeObserver(new ViewChanges(() -> index.current, registry, kinds, scheduler));
    }

    private static ViewReads reads(VariableDeclaration variable, ReadRole role) {
        return new ViewReads(Map.of(new ReadKey(variable.scope(), variable.id()), EnumSet.of(role)), false);
    }

    private void publish(Map<ViewRef, ViewReads> reads) {
        index.current = ReadsIndex.of(reads);
    }

    private ViewerRecord open(UUID player, NamespacedId definition) {
        ViewerRecord record = GuiViewerState.record(player, "p", definition, Map.of());
        registry.begin(record);
        return record;
    }

    private boolean writeCoins(UUID player, double value) {
        return variables.service().store(VariableScope.PLAYER).write(coins, player, value);
    }

    /** Moves the clock one tick and runs what is due, in order, including what those tasks queue for a later tick. */
    private void tick() {
        tick.incrementAndGet();
        List<Task> due = queue.stream().filter(task -> task.due() <= tick.get())
                .sorted(Comparator.comparingLong(Task::order)).toList();
        queue.removeAll(due);
        due.forEach(task -> task.run().run());
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            tick();
        }
    }

    @Test
    void aHundredWritesInATickAreOneAutomaticRender() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        open(alex, MENU);

        for (int i = 0; i < 100; i++) {
            writeCoins(alex, i);
        }
        ticks(1);

        assertThat(gui.renders).hasValue(1);
        ticks(5);
        assertThat(gui.renders).hasValue(1);
    }

    @Test
    void anIdleViewerHasNoTaskAndNeverRenders() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        open(alex, MENU);
        open(blake, OTHER);

        writeCoins(blake, 4d);
        ticks(200);

        assertThat(queue).isEmpty();
        assertThat(gui.renders).hasValue(0);
    }

    @Test
    void aWriteIsRenderedAtTheNextTickNotInline() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        open(alex, MENU);

        writeCoins(alex, 1d);

        assertThat(gui.renders).hasValue(0);
        ticks(1);
        assertThat(gui.renders).hasValue(1);
    }

    @Test
    void theIntervalHoldsALaterWriteBackUntilItHasPassed() {
        gui.interval = 5;
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        open(alex, MENU);
        writeCoins(alex, 1d);
        ticks(1);
        assertThat(gui.renders).hasValue(1);

        writeCoins(alex, 2d);
        ticks(3);
        assertThat(gui.renders).hasValue(1);
        ticks(2);
        assertThat(gui.renders).hasValue(2);
    }

    @Test
    void aWakeForAWindowThatWasReplacedRendersNothing() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        ViewerRecord old = open(alex, MENU);
        writeCoins(alex, 1d);

        ViewerRecord replacement = open(alex, MENU);
        ticks(2);

        assertThat(gui.rendered).doesNotContain(old, replacement);
        assertThat(gui.renders).hasValue(0);
    }

    @Test
    void aWriteDuringARenderIsShownByTheNextOne() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        ViewerRecord record = open(alex, MENU);
        AtomicInteger writes = new AtomicInteger();
        gui.duringRender = () -> {
            if (writes.getAndIncrement() == 0) {
                writeCoins(alex, 9d);
            }
        };

        writeCoins(alex, 1d);
        ticks(1);
        assertThat(gui.renders).hasValue(1);
        assertThat(record.isDirty()).isTrue();
        ticks(1);

        assertThat(gui.renders).hasValue(2);
        assertThat(record.isDirty()).isFalse();
    }

    @Test
    void anExplicitRefreshRendersNowAndTheQueuedWakeIsANoOp() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        ViewerRecord record = open(alex, MENU);
        writeCoins(alex, 1d);

        assertThat(scheduler.refreshNow(record)).isTrue();
        assertThat(gui.renders).hasValue(1);
        ticks(3);

        assertThat(gui.renders).hasValue(1);
    }

    @Test
    void anExplicitRefreshIgnoresTheIntervalAndIsNotCountedAgainstIt() {
        gui.interval = 50;
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        ViewerRecord record = open(alex, MENU);

        scheduler.refreshNow(record);
        scheduler.refreshNow(record);
        scheduler.refreshSoon(record);
        ticks(1);

        assertThat(gui.renders).hasValue(3);
        writeCoins(alex, 1d);
        ticks(1);
        assertThat(gui.renders).hasValue(4);
    }

    @Test
    void anExplicitRefreshOfAStaleRecordRendersNothing() {
        ViewerRecord old = open(alex, MENU);
        open(alex, MENU);

        assertThat(scheduler.refreshNow(old)).isFalse();
        assertThat(gui.renders).hasValue(0);
    }

    @Test
    void aFrozenFrameLeavesTheBitSetAndRendersTheLatestStateAfterTheRelease() {
        publish(Map.of(new ViewRef("gui", MENU), reads(coins, ReadRole.CONTENT)));
        ViewerRecord record = open(alex, MENU);
        gui.outcome = ViewKind.RenderOutcome.FROZEN;

        writeCoins(alex, 1d);
        ticks(4);

        assertThat(gui.renders).hasValue(0);
        assertThat(record.isDirty()).isTrue();

        gui.outcome = ViewKind.RenderOutcome.RENDERED;
        ticks(1);

        assertThat(gui.renders).hasValue(1);
        assertThat(record.isDirty()).isFalse();
    }

    @Test
    void aServerWriteRendersEveryViewerOnceAndAnUnrelatedOneNot() {
        publish(Map.of(new ViewRef("gui", MENU), reads(season, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);
        ViewerRecord b = open(blake, MENU);
        open(UUID.randomUUID(), OTHER);

        variables.service().store(VariableScope.SERVER).write(season, null, 2d);
        ticks(1);

        assertThat(gui.rendered).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void aWriteToAVisibilityReadReachesAPlayerWithNoVisibleViewAndAttachesIt() {
        publish(Map.of(new ViewRef("hud", BAR), reads(hudOn, ReadRole.VISIBILITY)));
        registry.candidate("hud", alex);
        registry.candidate("hud", blake);

        ticks(50);
        assertThat(hud.evaluations).hasValue(0);
        assertThat(registry.current(alex, "gui")).isEmpty();

        writeHud(alex, true);
        ticks(1);

        assertThat(hud.evaluations).hasValue(1);
        assertThat(registry.current(alex, "gui")).isPresent();
        assertThat(registry.current(blake, "gui")).isEmpty();
    }

    @Test
    void aCandidateOfAPlayerWhoLeftIsNotEvaluated() {
        publish(Map.of(new ViewRef("hud", BAR), reads(hudOn, ReadRole.VISIBILITY)));
        registry.candidate("hud", alex);
        writeHud(alex, true);

        registry.forget(alex);
        ticks(2);

        assertThat(hud.evaluations).hasValue(0);
    }

    private void writeHud(UUID player, boolean on) {
        variables.service().store(VariableScope.PLAYER).write(hudOn, player, on);
    }
}
