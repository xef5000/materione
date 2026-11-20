package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.CompileEnvironment;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.core.runtime.RuntimeVariables;
import dev.materione.plugin.logic.action.SetVarAction;
import dev.materione.plugin.logic.condition.VarCondition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The item scope through {@code set_var}, the {@code var} condition and an expression, against the real service and
 * two stacks (logic.md section 38): the unit-level form of "two swords, two levels".
 */
class ItemScopeFlowTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final NamespacedId LEVEL = NamespacedId.of("rpg", "level");
    private static final VariableDeclaration LEVEL_DECL =
            new VariableDeclaration(LEVEL, VariableScope.ITEM, VariableType.NUMBER, 1, "Weapon level");

    @TempDir
    Path dir;

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final SetVarAction setVar = new SetVarAction();
    private final VarCondition varCondition = new VarCondition();

    private VariableService service() {
        var map = new LinkedHashMap<NamespacedId, VariableDeclaration>();
        map.put(LEVEL, LEVEL_DECL);
        return new VariableService(
                new PdcVariableStore(uuid -> Optional.empty()),
                new ServerVariableStore(dir.resolve("variables.json"), warning -> { }),
                new PdcStackVariableStore(owner -> PdcVariableStore.Access.OWNED),
                player -> Optional.empty(),
                () -> new RuntimeVariables(map), diagnostics::add);
    }

    private static ParamReader reader(Map<String, Object> params) {
        return ParamReader.of(params, EngineExpressions.INSTANCE,
                CompileEnvironment.of("rpg", id -> LEVEL.equals(id) ? Optional.of(LEVEL_DECL) : Optional.empty()));
    }

    private static Map<String, Object> params(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private MapLogicContext about(VariableService service, StackVariablesTest.FakeStack stack) {
        return new MapLogicContext(Map.of()).withVariables(
                service.bind(STEVE, stack == null ? null : stack.ref(STEVE)));
    }

    private ActionOutcome levelUp(MapLogicContext context) {
        return setVar.execute(setVar.parse(reader(params("var", "item.level", "op", "add", "value", 1))), context);
    }

    @Test
    void twoSwordsKeepTwoLevels() {
        VariableService service = service();
        var first = new StackVariablesTest.FakeStack("rpg:sword");
        var second = new StackVariablesTest.FakeStack("rpg:sword");

        assertThat(levelUp(about(service, first))).isEqualTo(ActionOutcome.OK); // 1 -> 2
        assertThat(levelUp(about(service, first))).isEqualTo(ActionOutcome.OK); // 2 -> 3
        assertThat(levelUp(about(service, second))).isEqualTo(ActionOutcome.OK); // 1 -> 2

        assertThat(about(service, first).value("vars.item.rpg.level")).isEqualTo(3.0);
        assertThat(about(service, second).value("vars.item.rpg.level")).isEqualTo(2.0);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void aStepThatNamesAnItemVariableNeedsTheItemRootAndRunsOnThePlayersThread() {
        ParamReader reader = reader(params("var", "item.level", "value", 4));
        SetVarAction.Params parsed = setVar.parse(reader);

        assertThat(reader.requiredRoots()).containsOnlyKeys("item");
        assertThat(reader.requiredRoots().get("item")).contains("rpg:level");
        assertThat(setVar.threading(parsed)).isEqualTo(Threading.PLAYER_REGION);

        ParamReader local = reader(params("var", "count", "value", 4));
        setVar.parse(local);
        assertThat(local.requiredRoots()).isEmpty();
    }

    @Test
    void theQualifiedSpellingTakesItsScopeFromTheDeclaration() {
        VariableService service = service();
        var sword = new StackVariablesTest.FakeStack("rpg:sword");
        MapLogicContext context = about(service, sword);

        var outcome = setVar.execute(setVar.parse(reader(params("var", "rpg:level", "value", 6))), context);

        assertThat(outcome).isEqualTo(ActionOutcome.OK);
        assertThat(context.value("vars.item.rpg.level")).isEqualTo(6.0);
    }

    @Test
    void theVarConditionReadsTheStacksValueAndAnExpressionSeesTheSameValue() {
        VariableService service = service();
        var sword = new StackVariablesTest.FakeStack("rpg:sword");
        MapLogicContext context = about(service, sword);
        VarCondition.Params atLeastThree = varCondition.parse(reader(params("var", "item.level", "op", ">=", "value", 3)));
        assertThat(varCondition.test(atLeastThree, context)).isFalse(); // the default, 1

        levelUp(context);
        levelUp(context);

        assertThat(varCondition.test(atLeastThree, context)).isTrue();
        assertThat(context.value("vars.item.rpg.level")).isEqualTo(3.0);
    }

    @Test
    void aRunWithNoStackGetsTheDefaultAndAFailedWriteAndOneDiagnostic() {
        VariableService service = service();
        MapLogicContext context = about(service, null);

        assertThat(context.value("vars.item.rpg.level")).isEqualTo(1.0);
        assertThat(context.value("vars.item.rpg.level")).isEqualTo(1.0);
        ActionOutcome outcome = levelUp(context);

        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        assertThat(((ActionOutcome.Failed) outcome).reason()).contains("no item stack");
        assertThat(diagnostics).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().code()).isEqualTo("MTR-0065");
            assertThat(diagnostic.message()).contains("rpg:level");
        });
    }

    @Test
    void resettingAnItemVariableReturnsTheStackToItsUntouchedBytes() {
        VariableService service = service();
        var sword = new StackVariablesTest.FakeStack("rpg:sword");
        MapLogicContext context = about(service, sword);
        levelUp(context);
        assertThat(sword.data.isEmpty()).isFalse();

        setVar.execute(setVar.parse(reader(params("var", "item.level", "op", "reset"))), context);

        assertThat(sword.data.isEmpty()).isTrue();
        assertThat(context.value("vars.item.rpg.level")).isEqualTo(1.0);
    }
}
