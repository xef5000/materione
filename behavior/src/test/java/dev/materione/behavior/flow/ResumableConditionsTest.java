package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.api.logic.variable.VariableScope;
import dev.materione.api.logic.variable.VariableType;
import dev.materione.behavior.expr.ExpressionParser;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.run.ManualScheduler;
import dev.materione.behavior.run.RunReadExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResumableConditionsTest {
    private final ManualScheduler scheduler = new ManualScheduler();
    private final RunReadExecutor executor = new RunReadExecutor(scheduler, "player");
    private final List<String> reads = new ArrayList<>();
    private final VariableDeclaration variable = new VariableDeclaration(
            NamespacedId.of("shop", "coins"), VariableScope.PLAYER, VariableType.NUMBER, 0, "");
    private boolean unavailable;
    private final MapLogicContext context = new MapLogicContext(Map.of()).withVariables(new VariableBinding() {
        @Override public Optional<VariableDeclaration> declaration(NamespacedId id) {
            return id.equals(variable.id()) ? Optional.of(variable) : Optional.empty();
        }
        @Override public Optional<Object> stored(VariableDeclaration declaration) {
            assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
            reads.add("player");
            if (unavailable) {
                throw new ReadUnavailableException("retired");
            }
            return Optional.of(3.0);
        }
        @Override public boolean store(VariableDeclaration declaration, Object value) { return false; }
    });

    @Test
    void orderedTreeHopsOnlyWhenTheShortCircuitNeedsThePlayer() {
        scheduler.current = Threading.GLOBAL;
        var first = expression("true");
        var live = expression("vars.player.shop.coins == 3");
        var skipped = expression("vars.player.shop.coins == 4");
        var tree = new AtLeastCond(2, List.of(first, live, skipped));
        var result = ResumableConditions.test(tree, context, executor).toCompletableFuture();

        assertThat(result).isNotDone();
        scheduler.runPending();
        assertThat(result.join()).isTrue();
        assertThat(reads).containsExactly("player");
        assertThat(scheduler.history).extracting(ManualScheduler.Scheduled::thread)
                .containsExactly(Threading.PLAYER_REGION);
    }

    @Test
    void unavailableUnderNotIsStillAFailureRatherThanAGrant() {
        scheduler.current = Threading.GLOBAL;
        unavailable = true;
        var result = ResumableConditions.test(new NotCond(expression("vars.player.shop.coins == 3")),
                context, executor).toCompletableFuture();
        scheduler.runPending();
        assertThatThrownBy(result::join).hasCauseInstanceOf(ReadUnavailableException.class);
        assertThat(reads).containsExactly("player");
    }

    private static Condition expression(String source) {
        return new ExpressionCond(source, ExpressionParser.parse(source).tree());
    }
}
