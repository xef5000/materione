package dev.materione.plugin.runtime.mechanics.builtin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRuntime;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class DurabilityMechanicTest {

    private static final NamespacedKey USED = new NamespacedKey("materione", "durability-used");
    private static final NamespacedKey MAX = new NamespacedKey("materione", "durability-max");

    @Test
    @SuppressWarnings("removal")
    void cancelsVanillaWearAndPersistsPdcUseCounts() {
        Map<NamespacedKey, Object> data = new HashMap<>();
        PersistentDataContainer pdc = proxy(PersistentDataContainer.class, (target, method, args) -> switch (
                method.getName()) {
            case "get" -> data.get(args[0]);
            case "set" -> {
                data.put((NamespacedKey) args[0], args[2]);
                yield null;
            }
            case "remove" -> {
                data.remove(args[0]);
                yield null;
            }
            default -> throw new AssertionError("unexpected PDC call: " + method.getName());
        });
        ItemMeta meta = proxy(ItemMeta.class, (target, method, args) -> {
            if (method.getName().equals("getPersistentDataContainer")) {
                return pdc;
            }
            throw new AssertionError("unexpected item-meta call: " + method.getName());
        });
        var stack = new TestStack(meta);
        Player player = proxy(Player.class,
                (target, method, args) -> { throw new AssertionError("unexpected player call: " + method.getName()); });

        MechanicRuntime runtime = new DurabilityMechanic().createRuntime(null);
        MechanicListener<PlayerItemDamageEvent> listener = listener(runtime);
        MechanicLookup lookup = lookup(stack, new DurabilityMechanic.Instance(3));

        PlayerItemDamageEvent first = new PlayerItemDamageEvent(player, stack, 1);
        listener.handler().accept(first, lookup);
        assertThat(first.isCancelled()).isTrue();
        assertThat(data).containsEntry(USED, 1).containsEntry(MAX, 3);
        assertThat(stack.getAmount()).isEqualTo(1);

        PlayerItemDamageEvent second = new PlayerItemDamageEvent(player, stack, 1);
        listener.handler().accept(second, lookup);
        assertThat(second.isCancelled()).isTrue();
        assertThat(data).containsEntry(USED, 2).containsEntry(MAX, 3);
        assertThat(stack.getAmount()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static MechanicListener<PlayerItemDamageEvent> listener(MechanicRuntime runtime) {
        return (MechanicListener<PlayerItemDamageEvent>) runtime.listeners().getFirst();
    }

    private static MechanicLookup lookup(ItemStack stack, MechanicInstance binding) {
        return new MechanicLookup() {
            @Override
            public <T extends MechanicInstance> Optional<T> onItem(NamespacedId item, Class<T> type) {
                return type.isInstance(binding) ? Optional.of(type.cast(binding)) : Optional.empty();
            }

            @Override
            public <T extends MechanicInstance> Optional<T> onItem(ItemStack candidate, Class<T> type) {
                return candidate == stack && type.isInstance(binding)
                        ? Optional.of(type.cast(binding))
                        : Optional.empty();
            }
        };
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

    private static final class TestStack extends ItemStack {
        private final ItemMeta meta;
        private int amount = 1;

        private TestStack(ItemMeta meta) {
            this.meta = meta;
        }

        @Override
        public Material getType() {
            return Material.IRON_SWORD;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public ItemMeta getItemMeta() {
            return meta;
        }

        @Override
        public boolean editMeta(Consumer<? super ItemMeta> consumer) {
            consumer.accept(meta);
            return true;
        }
    }
}
