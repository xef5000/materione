package dev.materione.plugin.logic.variable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.variable.StackRef;
import dev.materione.api.logic.variable.VariableBinding;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.logic.action.LaunchAction;
import dev.materione.plugin.logic.action.StackEditAction;
import dev.materione.plugin.logic.world.ProjectileLauncher;
import dev.materione.plugin.logic.world.Regions;
import dev.materione.plugin.logic.world.RunStacks;
import dev.materione.plugin.logic.world.WorldVerbs;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

/**
 * The verbs that read "the stack the run is about" (logic.md §38, §41): {@code stack.edit} patches it and {@code launch}
 * stamps its identity on the projectile. Both ask the run's binding for the same handle the item scope uses, so this is
 * where the stack is built: a stand-in with data and an item meta, behind the real {@link BukkitStackRef}.
 */
class StackVerbsTest {

    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** A stack as the verbs see it: its data, an item meta that records edits, an identity and whether it exists. */
    private static final class Stack implements StackPdc {
        final PersistentDataContainer data = VariableTestSupport.fakeContainer();
        final List<String> edits = new ArrayList<>();
        String identity;
        boolean gone;
        boolean damageable = true;
        int maxDamage = 100;

        Stack(String identity) {
            this.identity = identity;
        }

        @Override
        public Optional<PersistentDataContainer> view() {
            return gone ? Optional.empty() : Optional.of(data);
        }

        @Override
        public boolean edit(Consumer<PersistentDataContainer> edit) {
            return !gone;
        }

        @Override
        public boolean editMeta(Consumer<ItemMeta> edit) {
            if (gone) {
                return false;
            }
            Class<?> type = damageable ? Damageable.class : ItemMeta.class;
            ItemMeta meta = (ItemMeta) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "displayName" -> record("name:" + plain(args[0]));
                        case "lore" -> record("lore:" + (args[0] == null ? "none"
                                : ((List<?>) args[0]).stream().map(Stack::plain).toList()));
                        case "setItemModel" -> record("model:" + args[0]);
                        case "setDamage" -> record("damage:" + args[0]);
                        case "hasMaxDamage" -> true;
                        case "getMaxDamage" -> maxDamage;
                        case "toString" -> "meta";
                        case "hashCode" -> 1;
                        case "equals" -> proxy == args[0];
                        default -> throw new AssertionError("unexpected meta call: " + method.getName());
                    });
            edit.accept(meta);
            return true;
        }

        private Object record(String edit) {
            edits.add(edit);
            return null;
        }

        private static String plain(Object component) {
            return PlainTextComponentSerializer.plainText().serialize((Component) component);
        }

        @Override
        public String identity() {
            return identity;
        }

        BukkitStackRef ref() {
            return new BukkitStackRef(STEVE, this);
        }
    }

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map, EngineExpressions.INSTANCE);
    }

    /** A binding whose stack is {@code ref}, as {@code RunVariables} reports the one the trigger declared. */
    private static VariableBinding holding(StackRef ref) {
        return new VariableBinding() {
            @Override
            public Optional<VariableDeclaration> declaration(NamespacedId id) {
                return Optional.empty();
            }

            @Override
            public Optional<Object> stored(VariableDeclaration declaration) {
                return Optional.empty();
            }

            @Override
            public boolean store(VariableDeclaration declaration, Object value) {
                return false;
            }

            @Override
            public Optional<StackRef> stack() {
                return Optional.ofNullable(ref);
            }
        };
    }

    private static MapLogicContext run(VariableBinding binding) {
        return new MapLogicContext(Map.of("vars", Map.of("rpg", Map.of("level", 5))))
                .withVariables(binding);
    }

    @Test
    void theVerbsAskTheRunsBindingForTheSameHandleTheItemScopeUses() {
        var stack = new Stack("rpg:sword");
        var context = run(holding(stack.ref()));

        assertThat(RunStacks.of(context)).isPresent();
        assertThat(RunStacks.identity(context)).contains("rpg:sword");
        assertThat(RunStacks.identity(run(holding(new Stack("").ref())))).isEmpty(); // a vanilla item has no identity
        assertThat(RunStacks.of(run(VariableBinding.NONE))).isEmpty();
    }

    @Test
    void stackEditPatchesTheNameLoreModelAndDamageOfTheRunsStack() {
        var stack = new Stack("rpg:sword");
        var action = new StackEditAction();
        var params = action.parse(reader("name", "<red>Bloodfang", "lore", List.of("Level 5", "Kills: 3"),
                "model", "rpg:blood_sword", "damage", 40));

        assertThat(action.execute(params, run(holding(stack.ref())))).isEqualTo(ActionOutcome.OK);

        assertThat(stack.edits).containsExactly("name:Bloodfang", "lore:[Level 5, Kills: 3]", "model:rpg:blood_sword",
                "damage:40");
    }

    @Test
    void textMayCarryTemplatesAndAnEmptyLoreListClearsTheLore() {
        var stack = new Stack("rpg:sword");
        var action = new StackEditAction();

        action.execute(action.parse(reader("lore", List.of("Level ${vars.rpg.level}"))), run(holding(stack.ref())));
        action.execute(action.parse(reader("lore", List.of())), run(holding(stack.ref())));

        assertThat(stack.edits).containsExactly("lore:[Level 5]", "lore:none");
    }

    @Test
    void damageIsClampedBelowTheItemsMaximumSoTheEditNeverBreaksIt() {
        var stack = new Stack("rpg:sword");
        var action = new StackEditAction();

        action.execute(action.parse(reader("damage", 5000)), run(holding(stack.ref())));

        assertThat(stack.edits).containsExactly("damage:99");
    }

    @Test
    void anItemWithNoDurabilityFailsTheDamageField() {
        var stack = new Stack("rpg:coin");
        stack.damageable = false;
        var action = new StackEditAction();

        assertThat(action.execute(action.parse(reader("damage", 1)), run(holding(stack.ref()))))
                .isEqualTo(ActionOutcome.failed("this item has no durability to set"));
    }

    @Test
    void aRunWithNoStackFailsAndWritesNothing() {
        var action = new StackEditAction();

        assertThat(action.execute(action.parse(reader("name", "x")), run(VariableBinding.NONE)))
                .isEqualTo(ActionOutcome.failed("the run has no item stack to edit"));
    }

    @Test
    void aStackThatWasReplacedWhileTheRunWasParkedIsRefusedLikeAnItemVariablesWrite() {
        var stack = new Stack("rpg:sword");
        var context = run(holding(stack.ref()));
        stack.identity = "rpg:other"; // the object now holds another item
        var action = new StackEditAction();

        assertThat(action.execute(action.parse(reader("name", "x")), context))
                .isEqualTo(ActionOutcome.failed("the item stack is gone or is now another item"));
        assertThat(stack.edits).isEmpty();

        stack.identity = "rpg:sword";
        stack.gone = true;
        assertThat(action.execute(action.parse(reader("name", "x")), context).isOk()).isFalse();
    }

    @Test
    void loadRejectsAnEmptyEditAndABadModelId() {
        var action = new StackEditAction();

        assertThatThrownBy(() -> action.parse(reader())).hasMessageContaining("at least one of");
        assertThatThrownBy(() -> action.parse(reader("model", "Not A Key!"))).hasMessageContaining("not a valid namespaced id");
        assertThatThrownBy(() -> action.parse(reader("damage", -1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action.parse(reader("lore", "one line"))).hasMessageContaining("must be a list of strings");
    }

    @Test
    void aLaunchedProjectileCarriesTheIdOfTheItemTheRunIsAbout() {
        var launches = new ArrayList<String>();
        ProjectileLauncher launcher = new ProjectileLauncher() {
            @Override
            public Optional<EntityType> projectile(String key) {
                return Optional.of(EntityType.SMALL_FIREBALL);
            }

            @Override
            public Optional<Entity> launch(ProjectileSource shooter, EntityType type, Vector velocity,
                    Optional<String> itemId) {
                launches.add(type + " item=" + itemId.orElse("-"));
                return Optional.of((Entity) Proxy.newProxyInstance(Entity.class.getClassLoader(),
                        new Class<?>[] {Entity.class}, (p, m, a) -> null));
            }
        };
        var verbs = new WorldVerbs(new Regions() {
            @Override
            public boolean ownedHere(Location location) {
                return true;
            }

            @Override
            public <T> java.util.concurrent.CompletionStage<T> at(Location location, Supplier<T> task) {
                return CompletableFuture.completedFuture(task.get());
            }
        }, null, launcher, name -> null, key -> null, text -> null, diagnostic -> { }, () -> 0L);
        Player shooter = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDead" -> false;
                    case "isValid" -> true;
                    case "getEyeLocation" -> new Location(null, 0, 65, 0, 0f, 0f);
                    case "toString" -> "shooter";
                    case "hashCode" -> 1;
                    case "equals" -> proxy == args[0];
                    default -> throw new AssertionError("unexpected player call: " + method.getName());
                });
        var launch = new LaunchAction(verbs);
        var withItem = run(holding(new Stack("rpg:fire_wand").ref())).with(Facets.PLAYER, shooter);
        var withoutItem = run(VariableBinding.NONE).with(Facets.PLAYER, shooter);

        assertThat(launch.execute(launch.parse(reader("projectile", "small_fireball")), withItem)).isEqualTo(ActionOutcome.OK);
        assertThat(launch.execute(launch.parse(reader("projectile", "small_fireball")), withoutItem)).isEqualTo(ActionOutcome.OK);

        assertThat(launches).containsExactly("SMALL_FIREBALL item=rpg:fire_wand", "SMALL_FIREBALL item=-");
        assertThat(NamespacedKey.fromString("materione:launched_by")).isNotNull();
    }
}
