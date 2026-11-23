package dev.materione.plugin.view;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableOp;
import dev.materione.api.logic.variable.VariableOps;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.plugin.gui.session.GuiViewerState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Session variables behind the store port (views.md section 3.4): the store is one more {@link VariableStore}, keyed
 * by the view instance, so a decorator around it sees every write, a never-written variable reads its default, a
 * replaced view starts empty, {@code view.back} carries the values to a new instance, and closing drops them.
 */
class SessionVariableStoreTest {

    private static final NamespacedId SHOP = NamespacedId.parse("demo:shop");

    private final UUID alex = UUID.randomUUID();
    private final ViewRegistry registry = new ViewRegistry();
    private final TestVariables variables = new TestVariables(registry);
    private final VariableDeclaration tab = variables.declare("demo", "tab", "all");
    private final VariableDeclaration page = variables.declare("demo", "page", 1d);

    private ViewerRecord open() {
        ViewerRecord record = GuiViewerState.record(alex, "Alex", SHOP, Map.of());
        registry.begin(record);
        return record;
    }

    /** What an observing decorator of the port would see: every write and every atomic mutation, in order. */
    private static final class Recording implements VariableStore {
        final List<String> seen = new ArrayList<>();
        private final VariableStore delegate;

        Recording(VariableStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public VariableScope scope() {
            return delegate.scope();
        }

        @Override
        public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
            return delegate.read(declaration, holder);
        }

        @Override
        public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
            seen.add("write " + declaration.id() + "=" + value + " @" + holder);
            return delegate.write(declaration, holder, value);
        }

        @Override
        public VariableOps.Outcome mutate(
                VariableDeclaration declaration, UUID holder, VariableOp operation, Object operand) {
            seen.add("mutate " + declaration.id() + " " + operation.id() + " @" + holder);
            return delegate.mutate(declaration, holder, operation, operand);
        }
    }

    @Test
    void aNeverWrittenSessionVariableReadsItsDeclaredDefault() {
        ViewerRecord view = open();

        assertThat(registry.sessions().read(tab, view.instance())).isEmpty();
        assertThat(variables.get(view, tab)).isEqualTo("all");
        assertThat(variables.get(view, page)).isEqualTo(1.0);
    }

    @Test
    void aWriteGoesThroughTheServiceStoreSoADecoratorSeesIt() {
        var recording = new Recording(registry.sessions());
        variables.service().sessionStore(recording);
        ViewerRecord view = open();

        var binding = variables.service().bind(alex, null, view.instance());
        assertThat(binding.store(tab, "tools")).isTrue();
        assertThat(binding.mutate(page, VariableOp.ADD, 2d).ok()).isTrue();

        assertThat(recording.seen).containsExactly(
                "write demo:tab=tools @" + view.instance(), "mutate demo:page add @" + view.instance());
        assertThat(binding.stored(tab)).contains("tools");
        assertThat(binding.stored(page)).contains(3.0);
    }

    @Test
    void theHolderIsTheViewInstanceNotThePlayer() {
        ViewerRecord first = open();
        registry.sessions().write(tab, first.instance(), "tools");
        ViewerRecord second = GuiViewerState.record(alex, "Alex", SHOP, Map.of());
        registry.admit(second);

        assertThat(registry.sessions().read(tab, second.instance())).isEmpty();
        assertThat(registry.sessions().read(tab, alex)).isEmpty();
        assertThat(registry.sessions().read(tab, first.instance())).contains("tools");
    }

    @Test
    void aWriteMustFitTheDeclaredType() {
        ViewerRecord view = open();

        assertThat(registry.sessions().write(page, view.instance(), "lots")).isFalse();
        assertThat(registry.sessions().read(page, view.instance())).isEmpty();
    }

    @Test
    void aNullWriteClearsTheValueSoTheDefaultReadsAgain() {
        ViewerRecord view = open();
        registry.sessions().write(tab, view.instance(), "tools");

        registry.sessions().write(tab, view.instance(), null);

        assertThat(variables.get(view, tab)).isEqualTo("all");
    }

    @Test
    void anInstanceThatIsNotOpenHoldsNothingAndRefusesWrites() {
        ViewerRecord view = open();
        registry.sessions().write(tab, view.instance(), "tools");

        registry.end(view, CloseReason.PLAYER);

        assertThat(registry.sessions().openInstances()).isZero();
        assertThat(registry.sessions().read(tab, view.instance())).isEmpty();
        assertThat(registry.sessions().write(tab, view.instance(), "armour")).isFalse();
        assertThat(registry.sessions().write(tab, null, "armour")).isFalse();
    }

    @Test
    void aReplacedViewIsANewInstanceThatStartsEmpty() {
        ViewerRecord first = open();
        registry.sessions().write(tab, first.instance(), "tools");

        ViewerRecord replacement = open();
        registry.end(first, CloseReason.REPLACED);

        assertThat(replacement.instance()).isNotEqualTo(first.instance());
        assertThat(variables.get(replacement, tab)).isEqualTo("all");
        assertThat(registry.sessions().read(tab, first.instance())).isEmpty();
    }

    @Test
    void backReopensTheSnapshotsValuesAsANewInstance() {
        ViewerRecord first = open();
        registry.sessions().write(tab, first.instance(), "tools");
        registry.sessions().write(page, first.instance(), 4d);
        ViewerRecord.Snapshot snapshot = first.snapshot();
        registry.sessions().write(tab, first.instance(), "armour"); // after the snapshot: not in it

        ViewerRecord back = GuiViewerState.record(alex, "Alex", snapshot.definition(), snapshot.args());
        registry.admit(back);
        back.restore(snapshot);

        assertThat(back.instance()).isNotEqualTo(first.instance());
        assertThat(snapshot.vars()).containsEntry(tab.id(), "tools").containsEntry(page.id(), 4.0);
        assertThat(variables.get(back, tab)).isEqualTo("tools");
        assertThat(variables.get(back, page)).isEqualTo(4.0);
        assertThat(variables.get(first, tab)).isEqualTo("armour");
    }

    @Test
    void aSnapshotOutlivesTheInstanceItWasTakenFrom() {
        ViewerRecord view = open();
        registry.sessions().write(tab, view.instance(), "tools");
        ViewerRecord.Snapshot snapshot = view.snapshot();

        registry.end(view, CloseReason.REPLACED);

        assertThat(snapshot.vars()).containsEntry(tab.id(), "tools");
    }

    @Test
    void aMutationIsAtomicAndKeepsTheTypeRules() {
        ViewerRecord view = open();

        assertThat(registry.sessions().mutate(page, view.instance(), VariableOp.ADD, 2d).value()).isEqualTo(3.0);
        assertThat(registry.sessions().mutate(page, view.instance(), VariableOp.TOGGLE, null).ok()).isFalse();
        assertThat(registry.sessions().mutate(page, null, VariableOp.ADD, 1d).ok()).isFalse();
        assertThat(variables.get(view, page)).isEqualTo(3.0);
    }

    @Test
    void forgettingAPlayerDropsTheirInstances() {
        ViewerRecord view = open();
        registry.sessions().write(tab, view.instance(), "tools");

        registry.forget(alex);

        assertThat(registry.sessions().openInstances()).isZero();
    }

    @Test
    void theStoreHoldsTheSessionScopeOnly() {
        assertThat(registry.sessions().scope()).isEqualTo(VariableScope.SESSION);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> variables.service().sessionStore(TestVariables.memory(VariableScope.PLAYER)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
