package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableStore;
import dev.materione.plugin.gui.GuiFixture;
import dev.materione.plugin.gui.event.GuiContexts;
import dev.materione.plugin.gui.session.GuiSession;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
        var session = new GuiSession(viewer, fixture.viewer.name(), id, Map.of());
        var open = GuiContexts.open(fixture.viewer, id, Map.of(), variables);
        var bound = GuiContexts.build(fixture.viewer, session, Map.of(), variables);
        var path = List.of("vars", "player", VariableTestSupport.COINS.namespace(),
                VariableTestSupport.COINS.value());
        assertThatThrownBy(() -> open.resolve(path)).isInstanceOf(ReadUnavailableException.class);
        assertThatThrownBy(() -> bound.resolve(path)).isInstanceOf(ReadUnavailableException.class);
    }
}
