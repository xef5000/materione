package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ParseRejection;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.plugin.gui.session.GuiViewerState;
import dev.materione.plugin.view.TestVariables;
import dev.materione.plugin.view.ViewRegistry;
import dev.materione.plugin.view.ViewerRecord;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.variable.VariableTestSupport;
import dev.materione.plugin.logic.variable.VariableTestSupport.Wired;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code set_var} v2 against the real variable service: every op, scope and type rule (logic.md §3.3). */
class SetVarActionTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path dir;

    private final SetVarAction action = new SetVarAction();

    private static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** A reader for a resource of pack {@code shop} that sees the shop declarations. */
    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE,
                CompileEnvironment.of("shop", id -> Optional.ofNullable(VariableTestSupport.declared().get(id))));
    }

    /** The diagnostic code a rejected parse is reported under. */
    private DiagnosticCode rejectionOf(Map<String, Object> params) {
        try {
            action.parse(reader(params));
        } catch (ParseRejection rejection) {
            return rejection.code();
        }
        throw new AssertionError("expected the step to be rejected: " + params);
    }

    /** A context for {@code player} (null for a run with none) over the wired service. */
    private MapLogicContext context(Wired wired, UUID player) {
        return new MapLogicContext(Map.of()).withVariables(wired.service().bind(player));
    }

    private Wired wiredWithPlayer(Map<UUID, PersistentDataContainer> containers) {
        return VariableTestSupport.wired(dir, containers);
    }

    @Nested
    class RunLocal {

        @Test
        void theM2ShapeStillStoresARunLocal() {
            var context = new MapLogicContext(Map.of());
            action.execute(action.parse(reader(params("name", "count", "value", 5))), context);
            action.execute(action.parse(reader(params("name", "label", "value", "gold"))), context);

            assertThat(context.locals()).containsEntry("count", 5).containsEntry("label", "gold");
            assertThat(context.value("vars.count")).isEqualTo(5);
        }

        @Test
        void addSubtractAndMultiplyWorkOnARunLocalNumber() {
            var context = new MapLogicContext(Map.of());
            run(context, params("var", "n", "value", 10));
            run(context, params("var", "n", "op", "add", "value", 5));
            run(context, params("var", "n", "op", "subtract", "value", 3));
            run(context, params("var", "n", "op", "multiply", "value", 2));

            assertThat(context.locals()).containsEntry("n", 24.0);
        }

        @Test
        void toggleAndRemoveAndResetWorkOnARunLocal() {
            var context = new MapLogicContext(Map.of());
            run(context, params("var", "flag", "value", false));
            run(context, params("var", "flag", "op", "toggle"));
            assertThat(context.locals()).containsEntry("flag", true);

            run(context, params("var", "flag", "op", "remove"));
            assertThat(context.locals()).doesNotContainKey("flag");
        }

        @Test
        void aTemplatedValueIsCoercedWhenItReadsAsTheNumber() {
            var context = new MapLogicContext(Map.of("item", Map.of("amount", 4d)));
            context.locals().put("n", 1d);
            run(context, params("var", "n", "op", "add", "value", "${item.amount}"));

            assertThat(context.locals()).containsEntry("n", 5.0);
        }

        @Test
        void arithmeticOnANonNumberLocalFailsTheStep() {
            var context = new MapLogicContext(Map.of());
            context.locals().put("n", "text");

            ActionOutcome outcome = action.execute(
                    action.parse(reader(params("var", "n", "op", "add", "value", 1))), context);

            assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
            assertThat(context.locals()).containsEntry("n", "text");
        }

        @Test
        void aScopeWordCannotBeALocalName() {
            assertThat(rejectionOf(params("name", "player", "value", 1))).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
        }

        private void run(MapLogicContext context, Map<String, Object> params) {
            assertThat(action.execute(action.parse(reader(params)), context)).isEqualTo(ActionOutcome.OK);
        }
    }

    @Nested
    class Persistent {

        @Test
        void aPlayerVariableIsStoredInTheContainerAndReadBackByAnExpression() {
            var containers = new HashMap<UUID, PersistentDataContainer>();
            containers.put(STEVE, VariableTestSupport.fakeContainer());
            Wired wired = wiredWithPlayer(containers);
            var context = context(wired, STEVE);

            run(context, params("var", "player.shop.coins", "value", 7));
            run(context, params("var", "player.coins", "op", "add", "value", 3));

            assertThat(context.value("vars.player.shop.coins")).isEqualTo(10.0);
            assertThat(wired.service().api().get(VariableTestSupport.COINS, STEVE)).contains(10.0);
        }

        @Test
        void theQualifiedSpellingTakesTheDeclaredScope() {
            var containers = new HashMap<UUID, PersistentDataContainer>();
            containers.put(STEVE, VariableTestSupport.fakeContainer());
            Wired wired = wiredWithPlayer(containers);
            var context = context(wired, STEVE);

            run(context, params("var", "shop:claimed", "op", "toggle"));

            assertThat(context.value("vars.player.shop.claimed")).isEqualTo(true);
        }

        @Test
        void aServerVariableIsSharedAndPersistedToTheFile() {
            Wired wired = wiredWithPlayer(new HashMap<>());
            var context = context(wired, null);

            run(context, params("var", "server.shop.season", "value", "winter"));

            assertThat(context.value("vars.server.shop.season")).isEqualTo("winter");
            assertThat(wired.server().isDirty()).isTrue();
        }

        @Test
        void removeAndResetReturnTheDeclaredDefault() {
            var containers = new HashMap<UUID, PersistentDataContainer>();
            containers.put(STEVE, VariableTestSupport.fakeContainer());
            Wired wired = wiredWithPlayer(containers);
            var context = context(wired, STEVE);
            run(context, params("var", "player.shop.coins", "value", 9));

            run(context, params("var", "player.shop.coins", "op", "reset"));

            assertThat(context.value("vars.player.shop.coins")).isEqualTo(0.0);
        }

        @Test
        void aPlayerVariableWithNoPlayerFailsTheStep() {
            Wired wired = wiredWithPlayer(new HashMap<>());
            var context = context(wired, null);

            ActionOutcome outcome = action.execute(
                    action.parse(reader(params("var", "player.shop.coins", "value", 1))), context);

            assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
            assertThat(((ActionOutcome.Failed) outcome).reason()).contains("no holder");
        }

        @Test
        void aPlayerVariableOfAnOfflinePlayerFailsTheStep() {
            Wired wired = wiredWithPlayer(new HashMap<>());
            var context = context(wired, STEVE);

            ActionOutcome outcome = action.execute(
                    action.parse(reader(params("var", "player.shop.coins", "value", 1))), context);

            assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        }

        @Test
        void playerWritesRunOnThePlayersRegionAndServerWritesCanRunInline() {
            var player = action.parse(reader(params("var", "player.shop.coins", "value", 1)));
            var server = action.parse(reader(params("var", "server.shop.season", "value", "x")));
            var local = action.parse(reader(params("var", "n", "value", 1)));

            assertThat(action.threading(player)).isEqualTo(Threading.PLAYER_REGION);
            assertThat(action.threading(server)).isEqualTo(Threading.ANY);
            assertThat(action.threading(local)).isEqualTo(Threading.ANY);
        }

        private void run(MapLogicContext context, Map<String, Object> params) {
            assertThat(action.execute(action.parse(reader(params)), context)).isEqualTo(ActionOutcome.OK);
        }
    }

    /** A session variable is declared like any other and held by one open view instance (views.md section 3.4). */
    @Nested
    class Session {

        private final ViewRegistry registry = new ViewRegistry();
        private final TestVariables variables = new TestVariables(registry);
        private final VariableDeclaration page = variables.declare("shop", "page", 0d);
        private final ViewerRecord view = GuiViewerState.record(
                STEVE, "Steve", NamespacedId.of("shop", "menu"), Map.of());

        private ParamReader sessionReader(Map<String, Object> params) {
            return ParamReader.of(params, EngineExpressions.INSTANCE, CompileEnvironment.of("shop",
                    id -> variables.service().declaration(id)));
        }

        private MapLogicContext inView() {
            registry.admit(view);
            return new MapLogicContext(Map.of())
                    .withVariables(variables.service().bind(STEVE, null, view.instance()));
        }

        private void run(MapLogicContext context, Map<String, Object> params) {
            assertThat(action.execute(action.parse(sessionReader(params)), context)).isEqualTo(ActionOutcome.OK);
        }

        @Test
        void aSessionVariableIsWrittenThroughTheStorePortOfItsView() {
            var context = inView();

            run(context, params("var", "session.page", "value", 2));
            run(context, params("var", "session.page", "op", "add", "value", 1));

            assertThat(variables.get(view, page)).isEqualTo(3.0);
            assertThat(context.value("vars.session.shop.page")).isEqualTo(3.0);
        }

        @Test
        void resetReturnsTheDeclaredDefault() {
            var context = inView();
            run(context, params("var", "session.page", "value", 5));

            run(context, params("var", "session.page", "op", "reset"));

            assertThat(context.value("vars.session.shop.page")).isEqualTo(0.0);
        }

        @Test
        void withNoOpenViewTheStepFails() {
            var context = new MapLogicContext(Map.of())
                    .withVariables(variables.service().bind(STEVE, null, null));

            ActionOutcome outcome = action.execute(
                    action.parse(sessionReader(params("var", "session.page", "value", 2))), context);

            assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
            assertThat(((ActionOutcome.Failed) outcome).reason()).contains("no open view holds");
        }

        @Test
        void aClosedViewRefusesTheWrite() {
            var context = inView();
            view.end(dev.materione.plugin.view.CloseReason.PLAYER);

            ActionOutcome outcome = action.execute(
                    action.parse(sessionReader(params("var", "session.page", "value", 2))), context);

            assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        }

        @Test
        void anUndeclaredSessionNameIsRejectedAtLoad() {
            assertThatThrownBy(() -> action.parse(sessionReader(params("var", "session.missing", "value", 1))))
                    .isInstanceOfSatisfying(ParseRejection.class, rejection ->
                            assertThat(rejection.code()).isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED));
        }

        @Test
        void aLiteralThatDoesNotFitTheDeclaredTypeIsRejectedAtLoad() {
            assertThatThrownBy(() -> action.parse(sessionReader(params("var", "session.page", "value", "lots"))))
                    .isInstanceOfSatisfying(ParseRejection.class, rejection ->
                            assertThat(rejection.code()).isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH));
        }
    }

    @Nested
    class LoadErrors {

        @Test
        void anUndeclaredPersistentNameIsRejectedWithItsCode() {
            assertThat(rejectionOf(params("var", "shop:gems", "value", 1)))
                    .isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
            assertThat(rejectionOf(params("var", "player.shop.gems", "value", 1)))
                    .isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
        }

        @Test
        void aScopeThatDoesNotMatchTheDeclarationIsRejected() {
            assertThat(rejectionOf(params("var", "server.shop.coins", "value", 1)))
                    .isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
            assertThat(rejectionOf(params("var", "player.shop.season", "value", "x")))
                    .isEqualTo(DiagnosticCodes.VARIABLE_UNDECLARED);
        }

        @Test
        void aLiteralOfTheWrongTypeIsRejected() {
            assertThat(rejectionOf(params("var", "player.shop.coins", "value", "lots")))
                    .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
            assertThat(rejectionOf(params("var", "player.shop.coins", "value", true)))
                    .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
            assertThat(rejectionOf(params("var", "server.shop.season", "value", 3)))
                    .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
        }

        @Test
        void anOperationTheTypeDoesNotTakeIsRejected() {
            assertThat(rejectionOf(params("var", "player.shop.coins", "op", "toggle")))
                    .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
            assertThat(rejectionOf(params("var", "player.shop.claimed", "op", "add", "value", 1)))
                    .isEqualTo(DiagnosticCodes.VARIABLE_TYPE_MISMATCH);
        }

        @Test
        void aMalformedStepIsRejectedWithTheGenericCode() {
            assertThatThrownBy(() -> action.parse(reader(params("var", "n", "op", "nope", "value", 1))))
                    .hasMessage("op must be one of set, add, subtract, multiply, toggle, remove, reset");
            assertThatThrownBy(() -> action.parse(reader(params("var", "n", "op", "add"))))
                    .hasMessage("value is required for add");
            assertThatThrownBy(() -> action.parse(reader(params("var", "n", "op", "toggle", "value", true))))
                    .hasMessage("toggle takes no value");
            assertThatThrownBy(() -> action.parse(reader(params("value", 1))))
                    .hasMessage("var is required (name is the M2 spelling of a run-local)");
        }

        @Test
        void aTemplateIsNotCheckedAtLoad() {
            var parsed = action.parse(reader(params("var", "player.shop.coins", "value", "${vars.x}")));

            assertThat(parsed.target()).isInstanceOf(SetVarAction.PersistentTarget.class);
        }

        @Test
        void theDeclaredIdIsTheIdentifierTheStoreUses() {
            var parsed = action.parse(reader(params("var", "shop:coins", "value", 1)));

            assertThat(((SetVarAction.PersistentTarget) parsed.target()).declaration().id())
                    .isEqualTo(NamespacedId.of("shop", "coins"));
        }
    }
}
