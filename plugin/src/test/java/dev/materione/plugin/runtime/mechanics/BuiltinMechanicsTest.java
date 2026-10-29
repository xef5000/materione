package dev.materione.plugin.runtime.mechanics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicContext;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRuntime;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.BoundAction;
import dev.materione.behavior.rule.RuleParser;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.logic.action.RunCommandAction;
import dev.materione.behavior.runtime.CooldownStore;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.platform.items.FoodItemModifier;
import dev.materione.platform.items.ItemListCodec;
import dev.materione.plugin.runtime.mechanics.builtin.BackpackMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.CommandsMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.DurabilityMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.FoodMechanic;
import dev.materione.plugin.runtime.mechanics.builtin.SoulboundMechanic;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class BuiltinMechanicsTest {

    private static final NamespacedId ITEM = NamespacedId.parse("sample:tool");

    @Test
    void registersFiveSelfContainedImplementationsWithTheExistingNamesAndSchemas() {
        assertThat(NativeMechanics.all()).extracting(Mechanic::name)
                .containsExactly("commands", "soulbound", "food", "durability", "backpack");
        assertThat(NativeMechanics.all()).allSatisfy(mechanic -> {
            assertThat(mechanic.getClass().getPackageName())
                    .isEqualTo("dev.materione.plugin.runtime.mechanics.builtin");
            assertThat(mechanic.describe().sinceVersion()).isEqualTo("2.0.0");
        });
        assertSchema(new CommandsMechanic(),
                List.of("on_right_click", "on_left_click", "as_player"),
                List.of(ParameterType.LIST, ParameterType.LIST, ParameterType.BOOLEAN),
                List.of(false, false, false));
        assertSchema(new SoulboundMechanic(), List.of(), List.of(), List.of());
        assertSchema(new FoodMechanic(),
                List.of("nutrition", "saturation", "can_always_eat", "effects"),
                List.of(ParameterType.INTEGER, ParameterType.DECIMAL, ParameterType.BOOLEAN, ParameterType.LIST),
                List.of(true, true, false, false));
        assertSchema(new DurabilityMechanic(), List.of("max"), List.of(ParameterType.INTEGER), List.of(true));
        assertSchema(new BackpackMechanic(),
                List.of("rows", "title"), List.of(ParameterType.INTEGER, ParameterType.STRING),
                List.of(true, false));
        assertThat(new CommandsMechanic().describe().parameters().get(2).defaultValue()).isEqualTo("false");
        assertThat(new FoodMechanic().describe().parameters().get(2).defaultValue()).isEqualTo("false");
        assertThat(new BackpackMechanic().describe().parameters().get(1).defaultValue()).isEqualTo("Backpack");
        assertThat(NativeMechanics.all().stream().filter(Mechanic::repeatable))
                .extracting(Mechanic::name).containsExactly("commands");
    }

    @Test
    void parsesTypedInstancesAndExistingDefaultsWithoutAStartedServer() {
        assertThat(new SoulboundMechanic().parse(configuration(Map.of())))
                .isEqualTo(new SoulboundMechanic.Instance());
        assertThat(new DurabilityMechanic().parse(configuration(Map.of("max", 500))))
                .isEqualTo(new DurabilityMechanic.Instance(500));
        assertThat(new BackpackMechanic().parse(configuration(Map.of("rows", 3))))
                .isEqualTo(new BackpackMechanic.Instance(3, "Backpack"));
        assertThat(new CommandsMechanic().parse(configuration(Map.of())))
                .isEqualTo(new CommandsMechanic.Instance(ITEM, List.of(), List.of(), false));
        var food = new FoodMechanic().parse(configuration(Map.of(
                "nutrition", 6, "saturation", 8.0,
                "effects", List.of(Map.of("type", "SPEED")))));
        assertThat(food.nutrition()).isEqualTo(6);
        assertThat(food.saturation()).isEqualTo(8.0f);
        assertThat(food.canAlwaysEat()).isFalse();
        assertThat(food.effects()).containsExactly(
                new FoodMechanic.Effect(NamespacedId.parse("minecraft:speed"), 0, 200));
        assertThat(new FoodMechanic().parse(configuration(Map.of(
                "nutrition", 0, "saturation", 0))).effects()).isEmpty();
    }

    @Test
    void rejectsMissingRequiredParametersAndNonIntegralOrOutOfRangeNumbers() {
        for (Mechanic mechanic : List.of(new DurabilityMechanic(), new BackpackMechanic(), new FoodMechanic())) {
            assertThatThrownBy(() -> mechanic.parse(configuration(Map.of())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (Object invalid : List.of(-1, 21, 1.5, "6", true, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThatThrownBy(() -> new FoodMechanic().parse(
                    configuration(Map.of("nutrition", invalid, "saturation", 1))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nutrition");
        }
        for (Object invalid : List.of(-0.1, 40.1, "8.0", true, Double.NaN, Double.NEGATIVE_INFINITY)) {
            assertThatThrownBy(() -> new FoodMechanic().parse(
                    configuration(Map.of("nutrition", 6, "saturation", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("saturation");
        }
        for (Object invalid : List.of(0, -1, 3.5, "500", (long) Integer.MAX_VALUE + 1)) {
            assertThatThrownBy(() -> new DurabilityMechanic().parse(configuration(Map.of("max", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max");
        }
        for (Object invalid : List.of(0, 7, 3.5, "3")) {
            assertThatThrownBy(() -> new BackpackMechanic().parse(configuration(Map.of("rows", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rows");
        }
    }

    @Test
    void acceptsNumericBoundaryValuesAndRequiresActualBooleansAndStrings() {
        assertThat(new FoodMechanic().parse(configuration(Map.of(
                "nutrition", 20, "saturation", 40, "can_always_eat", true))).canAlwaysEat()).isTrue();
        assertThat(new DurabilityMechanic().parse(configuration(Map.of("max", Integer.MAX_VALUE))).max())
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(new BackpackMechanic().parse(configuration(Map.of("rows", 6, "title", "&aBag"))).title())
                .isEqualTo("&aBag");
        for (Object invalid : List.of("true", 1, "false")) {
            assertThatThrownBy(() -> new FoodMechanic().parse(configuration(Map.of(
                    "nutrition", 6, "saturation", 8, "can_always_eat", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("can_always_eat");
            assertThatThrownBy(() -> new CommandsMechanic().parse(configuration(Map.of("as_player", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("as_player");
        }
        for (Object invalid : List.of("", " ", 3, true)) {
            assertThatThrownBy(() -> new BackpackMechanic().parse(
                    configuration(Map.of("rows", 3, "title", invalid))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        }
    }

    @Test
    void validatesEffectShapesNamesAndNonnegativeIntegralDurationsAndAmplifiers() {
        for (Object invalid : List.of(
                "speed", List.of("speed"), List.of(Map.of()), List.of(Map.of("type", "bad name")),
                List.of(Map.of("type", "minecraft:speed:extra")),
                List.of(Map.of("type", "speed", "amplifier", -1)),
                List.of(Map.of("type", "speed", "amplifier", 1.5)),
                List.of(Map.of("type", "speed", "amplifier", "1")),
                List.of(Map.of("type", "speed", "duration_ticks", -1)),
                List.of(Map.of("type", "speed", "duration_ticks", 1.5)),
                List.of(Map.of("type", "speed", "duration_ticks", "200")))) {
            assertThatThrownBy(() -> new FoodMechanic().parse(configuration(Map.of(
                    "nutrition", 6, "saturation", 8, "effects", invalid))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        var unknown = new FoodMechanic().parse(configuration(Map.of(
                "nutrition", 6, "saturation", 8,
                "effects", List.of(Map.of("type", "addon:unknown", "amplifier", 0, "duration_ticks", 0)))));
        assertThat(unknown.effects()).containsExactly(
                new FoodMechanic.Effect(NamespacedId.parse("addon:unknown"), 0, 0));
        assertThatThrownBy(() -> unknown.effects().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void strictlyValidatesBothCommandLists() {
        for (String key : List.of("on_right_click", "on_left_click")) {
            for (Object invalid : List.of("spawn", List.of(1), List.of(""), List.of(" "))) {
                assertThatThrownBy(() -> new CommandsMechanic().parse(configuration(Map.of(key, invalid))))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
            }
        }
    }

    @Test
    void contributesImmutableSharedCommandRulesWithUniqueDeterministicIds() {
        var commands = new ArrayList<>(List.of("say %player%", "spawn"));
        var instance = new CommandsMechanic().parse(configuration(Map.of(
                "on_right_click", commands, "on_left_click", List.of("help"), "as_player", true)));
        commands.clear();
        var rules = instance.behaviorRules();
        var compiled = RuleParser.compile(ITEM, rules,
                LogicTestSupport.withBuiltIns(new RegistryService()).lookup());

        assertThat(compiled.problems()).isEmpty();
        assertThat(compiled.rules()).hasSize(2);
        assertThat(compiled.rules()).extracting(rule -> rule.on().detail().orElseThrow())
                .containsExactly("right", "left");
        assertThat(compiled.rules()).extracting(rule -> rule.id()).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).contains(ITEM.toString(), "materione:commands"));
        assertThat(compiled.rules().getFirst().actions()).extracting(BoundAction::id)
                .containsExactly("run_command", "run_command");
        assertThat(compiled.rules().getFirst().actions().getFirst().params())
                .isInstanceOfSatisfying(RunCommandAction.Params.class, params -> {
                    assertThat(params.command().source()).isEqualTo("say ${player.name}");
                    assertThat(params.sender()).isEqualTo(RunCommandAction.Sender.PLAYER);
                });
        assertThat(instance.behaviorRules()).isEqualTo(rules);
        assertThatThrownBy(() -> rules.clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> rules.getFirst().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> instance.onRightClick().clear()).isInstanceOf(UnsupportedOperationException.class);
        var other = new CommandsMechanic().parse(new MechanicConfiguration(
                NamespacedId.parse("sample:other"), Map.of("on_right_click", List.of("spawn"))));
        assertThat(other.behaviorRules().getFirst().get("id")).isNotEqualTo(rules.getFirst().get("id"));
        assertThat(new CommandsMechanic().parse(configuration(Map.of())).behaviorRules()).isEmpty();
        assertThat(new CommandsMechanic().createRuntime(null).listeners()).isEmpty();
    }

    @Test
    void commandRulesExecuteExactlyOncePerMatchingClickThroughTheSharedEngine() {
        var diagnostics = new ArrayList<Diagnostic>();
        var executed = new ArrayList<String>();
        var deferred = new ArrayList<Runnable>();
        var engine = new RuleEngine((anchor, delay, task) -> deferred.add(task), diagnostics::add, new CooldownStore());
        // the real triggers, and a recording stand-in for run_command (the real one dispatches to Bukkit)
        var logic = LogicTestSupport.newService(new RegistryService());
        LogicTestSupport.registerTriggers(logic);
        logic.actions().register(LogicTestSupport.owner("materione"),
                NamespacedId.of("materione", "run_command"), new RecordingCommand(executed));
        for (boolean asPlayer : List.of(false, true)) {
            var instance = new CommandsMechanic().parse(configuration(Map.of(
                    "on_right_click", List.of("say %player%", "spawn"),
                    "on_left_click", List.of("help %player%"), "as_player", asPlayer)));
            var parsed = RuleParser.compile(ITEM, instance.behaviorRules(), logic.lookup());
            assertThat(parsed.problems()).isEmpty();
            var rules = parsed.rules();
            var context = new MapLogicContext(Map.of("player", Map.of("name", "Alex", "uuid", "test")));
            engine.fire(rules, new BehaviorEvent("click", "right", context, null, null));
            engine.fire(rules, new BehaviorEvent("click", "left", context, null, null));
        }
        assertThat(executed).isEmpty();
        assertThat(deferred).hasSize(6);
        deferred.forEach(Runnable::run);
        assertThat(executed).containsExactly(
                "console:say Alex", "console:spawn", "console:help Alex",
                "player:say Alex", "player:spawn", "player:help Alex");
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void foodContributesAnIdempotentPlatformHookWithoutTouchingOtherStackState() {
        var values = new HashMap<String, Object>();
        FoodComponent food = proxy(FoodComponent.class, (target, method, args) -> {
            values.put(method.getName(), args[0]);
            return null;
        });
        ItemMeta meta = proxy(ItemMeta.class, (target, method, args) -> switch (method.getName()) {
            case "getFood" -> food;
            case "setFood" -> {
                assertThat(args[0]).isSameAs(food);
                yield null;
            }
            default -> throw new AssertionError("hook touched unrelated meta: " + method.getName());
        });
        var stack = new EditingStack(meta);
        var instance = new FoodMechanic().parse(configuration(Map.of(
                "nutrition", 6, "saturation", 8, "can_always_eat", true)));
        assertThat(instance.itemModifiers()).containsExactly(new FoodItemModifier(6, 8f, true));
        instance.itemModifiers().getFirst().apply(stack);
        instance.itemModifiers().getFirst().apply(stack);
        assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of(
                "setNutrition", 6, "setSaturation", 8f, "setCanAlwaysEat", true));
        assertThat(instance.behaviorRules()).isEmpty();
        assertThat(new SoulboundMechanic().parse(configuration(Map.of())).itemModifiers()).isEmpty();
    }

    @Test
    void nativeListenersDeclareTheOriginalPrioritiesCancellationAndOwningThread() {
        var food = new FoodMechanic().createRuntime(null);
        var durability = new DurabilityMechanic().createRuntime(null);
        var soulbound = new SoulboundMechanic().createRuntime(null);
        var backpack = new BackpackMechanic().createRuntime(null);
        assertListener(food, PlayerItemConsumeEvent.class, EventPriority.MONITOR, true);
        assertListener(durability, PlayerItemDamageEvent.class, EventPriority.HIGH, true);
        assertListener(soulbound, PlayerDeathEvent.class, EventPriority.HIGH, false);
        assertListener(soulbound, PlayerRespawnEvent.class, EventPriority.MONITOR, false);
        assertListener(soulbound, PlayerJoinEvent.class, EventPriority.MONITOR, false);
        assertListener(backpack, PlayerInteractEvent.class, EventPriority.HIGH, false);
        assertListener(backpack, InventoryCloseEvent.class, EventPriority.MONITOR, false);
        assertThat(food.listeners()).hasSize(1);
        assertThat(durability.listeners()).hasSize(1);
        assertThat(soulbound.listeners()).hasSize(3);
        assertThat(backpack.listeners()).hasSize(2);
    }

    @Test
    void soulboundNeverStashesInventoryThatIsAlreadyKept() {
        Player player = proxy(Player.class, (target, method, args) -> {
            throw new AssertionError("kept inventory must not be touched");
        });
        DamageSource damage = proxy(DamageSource.class, (target, method, args) -> null);
        var drops = new ArrayList<ItemStack>(Collections.singletonList(null));
        var event = new PlayerDeathEvent(player, damage, drops, 0, Component.empty());
        event.setKeepInventory(true);
        listener(new SoulboundMechanic().createRuntime(null), PlayerDeathEvent.class)
                .handler().accept(event, NO_LOOKUP);
        assertThat(drops).hasSize(1);
    }

    @Test
    void soulboundKeepsTheDurableStashUntilScheduledAndReadsItOnceAtExecution() {
        NamespacedKey stashKey = new NamespacedKey("materione", "soulbound");
        byte[] raw = ItemListCodec.encode(Collections.singletonList(null));
        var storage = new HashMap<NamespacedKey, Object>();
        storage.put(stashKey, raw);
        var tasks = new ArrayList<Runnable>();
        var owners = new ArrayList<Plugin>();
        var restored = new ArrayList<ItemStack[]>();
        PersistentDataContainer pdc = proxy(PersistentDataContainer.class, (target, method, args) -> {
            if (method.getName().equals("get")) {
                return storage.get(args[0]);
            }
            if (method.getName().equals("set")) {
                storage.put((NamespacedKey) args[0], args[2]);
                return null;
            }
            throw new AssertionError("unexpected PDC call: " + method.getName());
        });
        PlayerInventory inventory = proxy(PlayerInventory.class, (target, method, args) -> {
            if (method.getName().equals("addItem")) {
                restored.add((ItemStack[]) args[0]);
                return new HashMap<Integer, ItemStack>();
            }
            throw new AssertionError("unexpected inventory call: " + method.getName());
        });
        Player player = proxy(Player.class, (target, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> pdc;
            case "getInventory" -> inventory;
            default -> throw new AssertionError("unexpected player call: " + method.getName());
        });
        Plugin plugin = proxy(Plugin.class, (target, method, args) -> null);
        SchedulingGateway scheduling = new SchedulingGateway() {
            @Override
            public void executeEntity(Plugin owner, Entity entity, Runnable task) {
                assertThat(entity).isSameAs(player);
                owners.add(owner);
                tasks.add(task);
            }

            @Override
            public void executeSync(Plugin owner, Location location, Runnable task) {
                throw new AssertionError("expected entity scheduler");
            }

            @Override
            public void executeAsync(Plugin owner, Runnable task) {
                throw new AssertionError("expected entity scheduler");
            }

            @Override
            public void executeGlobal(Plugin owner, Runnable task) {
                throw new AssertionError("expected entity scheduler");
            }
        };
        var runtime = new SoulboundMechanic().createRuntime(new TestContext(plugin, scheduling));
        var join = listener(runtime, PlayerJoinEvent.class);
        join.handler().accept(new PlayerJoinEvent(player, Component.empty()), NO_LOOKUP);
        join.handler().accept(new PlayerJoinEvent(player, Component.empty()), NO_LOOKUP);
        assertThat(storage.get(stashKey)).isSameAs(raw);
        assertThat(owners).containsExactly(plugin, plugin);
        tasks.forEach(Runnable::run);
        assertThat(restored).hasSize(1);
        assertThat(restored.getFirst()).isEmpty();
        assertThat((byte[]) storage.get(stashKey)).isEmpty();
    }

    private static MechanicConfiguration configuration(Map<String, Object> parameters) {
        return new MechanicConfiguration(ITEM, parameters);
    }

    private static void assertSchema(
            Mechanic mechanic, List<String> names, List<ParameterType> types, List<Boolean> required) {
        assertThat(mechanic.describe().parameters()).extracting(ParameterSchema::id).containsExactlyElementsOf(names);
        assertThat(mechanic.describe().parameters()).extracting(ParameterSchema::type).containsExactlyElementsOf(types);
        assertThat(mechanic.describe().parameters()).extracting(ParameterSchema::required).containsExactlyElementsOf(required);
    }

    private static <E extends Event> void assertListener(
            MechanicRuntime runtime, Class<E> type, EventPriority priority, boolean ignoreCancelled) {
        var listener = listener(runtime, type);
        assertThat(listener.priority()).isEqualTo(priority);
        assertThat(listener.ignoreCancelled()).isEqualTo(ignoreCancelled);
        assertThat(listener.thread()).isEqualTo(MechanicListener.ThreadClass.REGION);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Event> MechanicListener<E> listener(MechanicRuntime runtime, Class<E> type) {
        return (MechanicListener<E>) runtime.listeners().stream()
                .filter(listener -> listener.eventType().equals(type)).findFirst().orElseThrow();
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (target, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> target == args[0];
                            case "hashCode" -> System.identityHashCode(target);
                            case "toString" -> type.getSimpleName() + "@"
                                    + Integer.toHexString(System.identityHashCode(target));
                            default -> throw new AssertionError("unexpected Object method: " + method.getName());
                        };
                    }
                    return handler.invoke(target, method, args);
                }));
    }

    private static final class EditingStack extends ItemStack {
        private final ItemMeta meta;

        private EditingStack(ItemMeta meta) {
            this.meta = meta;
        }

        @Override
        public boolean editMeta(Consumer<? super ItemMeta> consumer) {
            consumer.accept(meta);
            return true;
        }
    }

    private record TestContext(Plugin plugin, SchedulingGateway scheduling) implements MechanicContext {
        @Override
        public DiagnosticsBus diagnostics() {
            throw new AssertionError("unexpected diagnostics lookup");
        }

        @Override
        public MechanicLookup snapshot() {
            throw new AssertionError("listener must use its event-entry lookup");
        }

        @Override
        public <T extends AutoCloseable> T track(T resource) {
            return resource;
        }
    }

    /** A stand-in for the real run_command that records "sender:rendered command" instead of dispatching. */
    private record RecordingCommand(List<String> executed)
            implements ActionType<RecordingCommand.Params> {
        record Params(String as, TemplateText command) {}

        @Override
        public String id() {
            return "run_command";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(SelfDescription.of("Run command", "Test double"),
                    LogicCategory.SERVER, null);
        }

        @Override
        public Threading threading() {
            return Threading.GLOBAL;
        }

        @Override
        public Params parse(ParamReader params) {
            return new Params(params.text("as", "console"), params.template("command"));
        }

        @Override
        public ActionOutcome execute(Params params, LogicContext context) {
            executed.add(params.as() + ":" + params.command().render(context));
            return ActionOutcome.OK;
        }
    }

    private static final MechanicLookup NO_LOOKUP = new MechanicLookup() {
        @Override
        public <T extends MechanicInstance> Optional<T> onItem(NamespacedId item, Class<T> type) {
            throw new AssertionError("unexpected mechanic lookup");
        }

        @Override
        public <T extends MechanicInstance> Optional<T> onItem(ItemStack stack, Class<T> type) {
            throw new AssertionError("unexpected mechanic lookup");
        }
    };
}
