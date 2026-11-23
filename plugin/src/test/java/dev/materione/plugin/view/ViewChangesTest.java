package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ViewFacet;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.plugin.gui.Viewer;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.open.OpenRequest;
import dev.materione.plugin.view.open.OpenResult;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The write observer (views.md section 5.3, acceptance cases 1 and 2): a write to a variable a view reads marks exactly
 * the viewers its scope reaches, on every path of the store port, and a hundred writes wake a viewer once.
 */
class ViewChangesTest {

    private static final NamespacedId MENU = NamespacedId.parse("demo:menu");
    private static final NamespacedId OTHER = NamespacedId.parse("demo:other");
    private static final NamespacedId MANA = NamespacedId.parse("rpg:mana");
    private static final ViewRef MENU_VIEW = new ViewRef("gui", MENU);
    private static final ViewRef OTHER_VIEW = new ViewRef("gui", OTHER);
    private static final ViewRef MANA_VIEW = new ViewRef("hud", MANA);

    /** A kind that forms its viewers by a condition, as a HUD will: only its facet matters to the observer. */
    private static final class PredicateKind implements ViewKind {

        @Override
        public String contentType() {
            return "hud";
        }

        @Override
        public ViewFacet facet() {
            return new ViewFacet(ViewFacet.ViewerRule.PREDICATE, ViewFacet.RerenderPolicy.ON_CHANGE, false, false);
        }

        @Override
        public int minInterval() {
            return 1;
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
            throw new UnsupportedOperationException();
        }
    }

    /** The wake hook of the tests: it records who was woken, in order. */
    private final List<ViewDirty> woken = new ArrayList<>();
    private final ViewRegistry registry = new ViewRegistry();
    private final ViewKinds kinds = new ViewKinds();
    private final TestVariables variables = new TestVariables(registry);
    private final AtomicReference<ReadsIndex> index = new AtomicReference<>(ReadsIndex.empty());
    private final UUID alex = UUID.randomUUID();
    private final UUID blake = UUID.randomUUID();
    private final VariableDeclaration coins = variables.declare("demo", "coins", VariableScope.PLAYER, 0d);
    private final VariableDeclaration season = variables.declare("demo", "season", VariableScope.SERVER, 1d);
    private final VariableDeclaration tab = variables.declare("demo", "tab", VariableScope.SESSION, "all");
    private final VariableDeclaration unread = variables.declare("demo", "unread", VariableScope.PLAYER, 0d);
    private final VariableDeclaration hudOn = variables.declare("rpg", "hud_on", VariableScope.PLAYER, false);
    private final VariableDeclaration realm = variables.declare("rpg", "realm", VariableScope.SERVER, "a");

    ViewChangesTest() {
        kinds.register(new PredicateKind());
        variables.service().writeObserver(new ViewChanges(index::get, registry, kinds, woken::add));
    }

    private static ViewReads reads(VariableDeclaration variable, ReadRole... roles) {
        return new ViewReads(Map.of(new ReadKey(variable.scope(), variable.id()), EnumSet.copyOf(List.of(roles))), false);
    }

    private void publish(Map<ViewRef, ViewReads> reads) {
        index.set(ReadsIndex.of(reads));
    }

    private ViewerRecord open(UUID player, NamespacedId definition) {
        ViewerRecord record = GuiViewerState.record(player, "p-" + player.toString().substring(0, 4), definition, Map.of());
        registry.begin(record);
        return record;
    }

    private void clean(ViewDirty... targets) {
        for (ViewDirty target : targets) {
            target.clearDirty();
        }
        woken.clear();
    }

    private boolean writePlayer(VariableDeclaration variable, UUID player, Object value) {
        return variables.service().store(VariableScope.PLAYER).write(variable, player, value);
    }

    @Test
    void aPlayerWriteMarksOnlyTheViewersOfThatPlayer() {
        publish(Map.of(MENU_VIEW, reads(coins, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);
        ViewerRecord b = open(blake, MENU);
        ViewerRecord elsewhere = open(UUID.randomUUID(), OTHER);

        assertThat(writePlayer(coins, alex, 5d)).isTrue();

        assertThat(a.isDirty()).isTrue();
        assertThat(b.isDirty()).isFalse();
        assertThat(elsewhere.isDirty()).isFalse();
        assertThat(woken).containsExactly(a);
    }

    @Test
    void aPlayerWriteDoesNotMarkADifferentDefinitionOfThatPlayer() {
        publish(Map.of(MENU_VIEW, reads(coins, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, OTHER);

        writePlayer(coins, alex, 5d);

        assertThat(a.isDirty()).isFalse();
        assertThat(woken).isEmpty();
    }

    @Test
    void aServerWriteMarksEveryViewerOfEveryReadingDefinitionAndNobodyElse() {
        publish(Map.of(MENU_VIEW, reads(season, ReadRole.CONTENT), OTHER_VIEW, reads(coins, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);
        ViewerRecord b = open(blake, MENU);
        ViewerRecord c = open(UUID.randomUUID(), OTHER);

        assertThat(variables.service().store(VariableScope.SERVER).write(season, null, 2d)).isTrue();

        assertThat(woken).containsExactlyInAnyOrder(a, b);
        assertThat(c.isDirty()).isFalse();
    }

    @Test
    void theAtomicOperationsOfTheServerStoreNotifyToo() {
        publish(Map.of(MENU_VIEW, reads(season, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);

        var outcome = variables.service().store(VariableScope.SERVER)
                .mutate(season, null, VariableOp.ADD, 3d);

        assertThat(outcome.ok()).isTrue();
        assertThat(woken).containsExactly(a);
    }

    @Test
    void aSessionWriteMarksThatWindowsRecordOnly() {
        publish(Map.of(MENU_VIEW, reads(tab, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);
        ViewerRecord b = open(blake, MENU);
        registry.admit(a);

        assertThat(variables.put(a, tab, "tools")).isTrue();
        assertThat(woken).containsExactly(a);
        clean(a);

        var outcome = variables.service().store(VariableScope.SESSION).mutate(tab, a.instance(), VariableOp.SET, "x");
        assertThat(outcome.ok()).isTrue();
        assertThat(woken).containsExactly(a);
        assertThat(b.isDirty()).isFalse();
    }

    @Test
    void aWriteThatFailedMarksNobody() {
        publish(Map.of(MENU_VIEW, reads(season, ReadRole.CONTENT), OTHER_VIEW, reads(tab, ReadRole.CONTENT)));
        open(alex, MENU);
        ViewerRecord ended = open(blake, OTHER);
        registry.end(ended, CloseReason.PLAYER);

        var refused = variables.service().store(VariableScope.SERVER).mutate(season, null, VariableOp.ADD, "not a number");
        boolean gone = variables.service().store(VariableScope.SESSION).write(tab, ended.instance(), "x");

        assertThat(refused.ok()).isFalse();
        assertThat(gone).isFalse();
        assertThat(woken).isEmpty();
    }

    @Test
    void aWriteNoViewReadsCostsNothing() {
        publish(Map.of(MENU_VIEW, reads(coins, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);

        writePlayer(unread, alex, 1d);

        assertThat(a.isDirty()).isFalse();
        assertThat(woken).isEmpty();
    }

    @Test
    void aHundredWritesWakeAViewerOnceAndTheNextWakeNeedsItToBeCleared() {
        publish(Map.of(MENU_VIEW, reads(coins, ReadRole.CONTENT)));
        ViewerRecord a = open(alex, MENU);

        for (int write = 0; write < 100; write++) {
            writePlayer(coins, alex, (double) write);
        }
        assertThat(woken).containsExactly(a);

        clean(a); // a render clears the bit before it reads
        writePlayer(coins, alex, 1000d);
        assertThat(woken).containsExactly(a);
    }

    @Test
    void aVisibilityWriteMarksThePlayersCandidateEvenWhenNoDefinitionIsVisibleToThem() {
        publish(Map.of(MANA_VIEW, reads(hudOn, ReadRole.VISIBILITY)));
        ViewCandidate a = registry.candidate("hud", alex);
        ViewCandidate b = registry.candidate("hud", blake);

        writePlayer(hudOn, alex, true);

        assertThat(registry.current(alex)).isEmpty();
        assertThat(woken).containsExactly(a);
        assertThat(b.isDirty()).isFalse();
    }

    @Test
    void aServerVisibilityWriteMarksEveryCandidateAndAContentWriteOnlyTheAttachedPlayers() {
        publish(Map.of(MANA_VIEW, new ViewReads(Map.of(
                new ReadKey(VariableScope.SERVER, realm.id()), Set.of(ReadRole.VISIBILITY),
                new ReadKey(VariableScope.SERVER, season.id()), Set.of(ReadRole.CONTENT)), false)));
        ViewCandidate a = registry.candidate("hud", alex);
        ViewCandidate b = registry.candidate("hud", blake);
        var attached = new ViewerRecord(alex, "alex", "hud", MANA, Map.of(), new ViewerPayload() { });
        registry.begin(attached);

        variables.service().store(VariableScope.SERVER).write(realm, null, "b");
        assertThat(woken).containsExactlyInAnyOrder(a, b);
        clean(a, b);

        variables.service().store(VariableScope.SERVER).write(season, null, 9d);
        assertThat(woken).containsExactly(a);
    }

    @Test
    void aCandidateEndsWithItsPlayersQuit() {
        publish(Map.of(MANA_VIEW, reads(hudOn, ReadRole.VISIBILITY)));
        registry.candidate("hud", alex);

        registry.forget(alex);
        writePlayer(hudOn, alex, true);

        assertThat(registry.candidateOrNull("hud", alex)).isNull();
        assertThat(woken).isEmpty();
    }

    @Test
    void aReloadThatAddsAReadIsFollowedByTheNextWrite() {
        ViewerRecord a = open(alex, MENU);
        writePlayer(coins, alex, 1d);
        assertThat(woken).isEmpty();

        var rebuilt = new LinkedHashMap<ViewRef, ViewReads>();
        rebuilt.put(MENU_VIEW, reads(coins, ReadRole.CONTENT));
        publish(rebuilt);
        writePlayer(coins, alex, 2d);

        assertThat(woken).containsExactly(a);
    }

    @Test
    void aViewThatReadsTwoVariablesIsWokenOncePerCleanDirtyTransition() {
        publish(Map.of(MENU_VIEW, new ViewReads(Map.of(
                new ReadKey(VariableScope.PLAYER, coins.id()), Set.of(ReadRole.CONTENT),
                new ReadKey(VariableScope.SERVER, season.id()), Set.of(ReadRole.CONTENT)), false)));
        ViewerRecord a = open(alex, MENU);

        writePlayer(coins, alex, 3d);
        variables.service().store(VariableScope.SERVER).write(season, null, 3d);

        assertThat(woken).containsExactly(a);
    }
}
