package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.StackVariableStore;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.core.runtime.RuntimeVariables;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.view.ViewerRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.materione.plugin.gui.session.GuiViewerState;

class GuiVariableBindingTest {
    @Test
    void openAndSessionContextsUseTheViewerBindingAndDoNotSubstituteDefaultsWhenUnavailable() {
        var fixture = new GuiFixture();
        UUID viewer = fixture.viewer.id();
        VariableStore player = new VariableStore() {
            @Override public VariableScope scope() { return VariableScope.PLAYER; }
            @Override public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
                assertThat(holder).isEqualTo(viewer);
                throw new ReadUnavailableException("not on player owner");
            }
            @Override public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
                return false;
            }
        };
        VariableStore server = new VariableStore() {
            @Override public VariableScope scope() { return VariableScope.SERVER; }
            @Override public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
                return Optional.empty();
            }
            @Override public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
                return false;
            }
        };
        var variables = new VariableService(player, server, VariableTestSupport::declarations, ignored -> {});
        var id = NamespacedId.of("demo", "shop");
        var session = GuiViewerState.record(viewer, fixture.viewer.name(), id, Map.of());
        var open = GuiContexts.open(fixture.viewer, id, Map.of(), variables);
        var bound = GuiContexts.build(fixture.viewer, session, Map.of(), variables);
        var path = List.of("vars", "player", VariableTestSupport.COINS.namespace(),
                VariableTestSupport.COINS.value());
        assertThatThrownBy(() -> open.resolve(path)).isInstanceOf(ReadUnavailableException.class);
        assertThatThrownBy(() -> bound.resolve(path)).isInstanceOf(ReadUnavailableException.class);
    }

    @Test
    void aGuiOpenRequirementRulesAndRendersUseTheSubjectStack() {
        var fixture = new GuiFixture();
        var id = NamespacedId.of("rpg", "upgrade");
        var levelId = NamespacedId.of("rpg", "level");
        var level = new VariableDeclaration(levelId, VariableScope.ITEM, VariableType.NUMBER, 1, "");
        StackRef stack = new StackRef() {
            @Override public UUID owner() { return fixture.viewer.id(); }
            @Override public Map<String, Object> itemView() {
                return Map.of("id", "rpg:bloodfang", "material", "diamond_sword", "amount", 1);
            }
        };
        VariableStore empty = new VariableStore() {
            @Override public VariableScope scope() { return VariableScope.PLAYER; }
            @Override public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
                return Optional.empty();
            }
            @Override public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
                return true;
            }
        };
        VariableStore server = new VariableStore() {
            @Override public VariableScope scope() { return VariableScope.SERVER; }
            @Override public Optional<Object> read(VariableDeclaration declaration, UUID holder) {
                return Optional.empty();
            }
            @Override public boolean write(VariableDeclaration declaration, UUID holder, Object value) {
                return true;
            }
        };
        StackVariableStore item = new StackVariableStore() {
            @Override public Optional<Object> read(VariableDeclaration declaration, StackRef holder) {
                return holder == stack ? Optional.of(4.0) : Optional.empty();
            }
            @Override public boolean write(VariableDeclaration declaration, StackRef holder, Object value) {
                return holder == stack;
            }
        };
        var variables = new VariableService(empty, server, item, player -> Optional.empty(),
                () -> new RuntimeVariables(Map.of(levelId, level)), ignored -> {});

        var session = GuiViewerState.record(fixture.viewer.id(), fixture.viewer.name(), id, Map.of(), stack);
        var open = GuiContexts.open(fixture.viewer, id, Map.of(), variables, stack);
        var bound = GuiContexts.build(fixture.viewer, session, Map.of(), variables);

        for (var context : List.of(open, bound)) {
            assertThat(context.resolve(List.of("vars", "item", "rpg", "level"))).isEqualTo(4.0);
            assertThat(context.resolve(List.of("view", "item", "id"))).isEqualTo("rpg:bloodfang");
            assertThat(context.resolve(List.of("item", "material"))).isEqualTo("diamond_sword");
        }
    }
}
