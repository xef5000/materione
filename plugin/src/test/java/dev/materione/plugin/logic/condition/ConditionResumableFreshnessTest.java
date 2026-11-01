package dev.materione.plugin.logic.condition;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.logic.ReadUnavailableException;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.run.RunReadExecutor;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.plugin.logic.action.PlayerFake;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.support.Quantity;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class ConditionResumableFreshnessTest {
    @Test
    void wrongWorldShortCircuitsBeforeAnyDynamicParameter() {
        var scheduler = new QueueScheduler();
        var player = new PlayerFake();
        var calls = new AtomicInteger();
        var params = new IsNearCondition.Params(Optional.of("nether"),
                number(dynamic("1", () -> {
                    calls.incrementAndGet();
                    throw new ReadUnavailableException("unsupported currency");
                })), literal(2), literal(3), literal(0));
        var result = new IsNearCondition().testResumable(
                params, player.context(), new RunReadExecutor(scheduler, "player")).toCompletableFuture();
        assertThat(result.join()).isFalse();
        assertThat(calls).hasValue(0);
        assertThat(scheduler.tasks).isEmpty();
    }

    @Test
    void nearReadsLocationAfterTheDynamicCoordinateOnThePlayerOwner() {
        var scheduler = new QueueScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var player = new PlayerFake();
        var coordinate = dynamic("10", () -> player.x = 10);
        var params = new IsNearCondition.Params(Optional.empty(), number(coordinate),
                literal(2), literal(3), literal(0));
        var result = new IsNearCondition().testResumable(params, player.context(), reads).toCompletableFuture();
        assertThat(result).isNotDone();
        scheduler.runNext();
        assertThat(result).isNotDone();
        scheduler.runNext();
        assertThat(result.join()).isTrue();
        assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
    }

    @Test
    void nearRechecksWorldAfterTheDynamicCoordinateSuspends() {
        var scheduler = new QueueScheduler();
        var player = new PlayerFake();
        var coordinate = dynamic("1", () -> player.worldName = "nether");
        var params = new IsNearCondition.Params(Optional.of("world"), number(coordinate),
                literal(2), literal(3), literal(0));
        var result = new IsNearCondition().testResumable(
                params, player.context(), new RunReadExecutor(scheduler, "player")).toCompletableFuture();
        scheduler.runNext();
        scheduler.runNext();
        assertThat(result.join()).isFalse();
    }

    @Test
    void itemSpaceReadsInventoryAfterDynamicSlotsOnThePlayerOwner() {
        var scheduler = new QueueScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var inventorySize = new AtomicInteger(36);
        var inventoryReads = new AtomicInteger();
        PlayerInventory inventory = proxy(PlayerInventory.class, name -> {
            if (name.equals("getContents")) {
                assertThat(scheduler.current).isEqualTo(Threading.PLAYER_REGION);
                inventoryReads.incrementAndGet();
                return new ItemStack[inventorySize.get()];
            }
            throw new AssertionError(name);
        });
        Player player = proxy(Player.class, name -> {
            if (name.equals("getInventory")) {
                return inventory;
            }
            throw new AssertionError(name);
        });
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player);
        var slots = dynamic("36", () -> inventorySize.set(35));
        var params = new HasItemSpaceCondition.Params(Optional.of(number(slots)), Optional.empty(), literal(1));
        var result = new HasItemSpaceCondition(ItemCatalog.none()).testResumable(params, context, reads)
                .toCompletableFuture();
        assertThat(inventoryReads).hasValue(0);
        scheduler.runNext();
        assertThat(inventoryReads).hasValue(0);
        scheduler.runNext();
        assertThat(result.join()).isFalse();
        assertThat(inventoryReads).hasValue(1);
    }

    @Test
    void itemSpaceDoesNotCaptureInventoryBeforeDynamicItemResolves() {
        var scheduler = new QueueScheduler();
        var reads = new RunReadExecutor(scheduler, "player");
        var inventoryReads = new AtomicInteger();
        Player player = proxy(Player.class, name -> {
            if (name.equals("getInventory")) {
                inventoryReads.incrementAndGet();
                throw new AssertionError("unresolved item needs no inventory");
            }
            throw new AssertionError(name);
        });
        var context = new MapLogicContext(Map.of()).with(Facets.PLAYER, player);
        var params = new HasItemSpaceCondition.Params(Optional.empty(),
                Optional.of(dynamic("not_a_real_item", () -> {})), literal(1));
        var result = new HasItemSpaceCondition(ItemCatalog.none()).testResumable(params, context, reads)
                .toCompletableFuture();
        assertThat(inventoryReads).hasValue(0);
        scheduler.runNext();
        scheduler.runNext();
        assertThat(result.join()).isFalse();
        assertThat(inventoryReads).hasValue(0);
    }

    private static Quantity literal(double value) {
        return new Quantity(value, null, 0, 100, true);
    }

    private static Quantity number(TemplateText text) {
        return new Quantity(0, text, 0, 100, true);
    }

    private static TemplateText dynamic(String value, Runnable onGlobal) {
        return new TemplateText() {
            @Override public String source() { return "${dynamic}"; }
            @Override public String render(LogicContext context) { throw new AssertionError("replayed"); }
            @Override public CompletionStage<String> renderResumable(LogicContext context, ReadExecutor reads) {
                return reads.on(Threading.GLOBAL, () -> {
                    onGlobal.run();
                    return value;
                });
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> answer) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (target, method, args) -> answer.apply(method.getName()));
    }

    private static final class QueueScheduler implements RunScheduler {
        private record Task(Threading owner, Runnable run) {}
        private final ArrayDeque<Task> tasks = new ArrayDeque<>();
        private Threading current = Threading.PLAYER_REGION;
        @Override public boolean canRunInline(Object anchor, Threading owner) {
            return owner == Threading.ANY || owner == current;
        }
        @Override public Handle schedule(Object anchor, Threading owner, long ticks, Runnable task, Runnable retired) {
            var entry = new Task(owner, task);
            tasks.add(entry);
            return () -> tasks.remove(entry);
        }
        void runNext() {
            var task = tasks.remove();
            current = task.owner();
            task.run().run();
        }
    }
}
