package dev.materione.plugin.runtime.mechanics.builtin;

import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicContext;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicListener.ThreadClass;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRuntime;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.List;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Per-instance wear lives in PDC, independent of the vanilla damage bar. */
public final class DurabilityMechanic implements Mechanic {

    private static final String USED_KEY = "durability-used";
    private static final String MAX_KEY = "durability-max";

    public record Instance(int max) implements MechanicInstance {}

    @Override
    public String name() {
        return "durability";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Durability",
                "Custom durability independent of the base material",
                List.of(new ParameterSchema("max", ParameterType.INTEGER, true, null,
                        "Uses before the item breaks", null, null)), "2.0.0");
    }

    @Override
    public Instance parse(MechanicConfiguration configuration) {
        return new Instance(configuration.integer("max", -1, 1, Integer.MAX_VALUE));
    }

    @Override
    public MechanicRuntime createRuntime(MechanicContext context) {
        return new MechanicRuntime() {
            @Override
            public List<MechanicListener<?>> listeners() {
                return List.of(new MechanicListener<>(
                        PlayerItemDamageEvent.class, EventPriority.HIGH, true, ThreadClass.REGION,
                        DurabilityMechanic::onDamage));
            }
        };
    }

    private static void onDamage(PlayerItemDamageEvent event, MechanicLookup lookup) {
        ItemStack stack = event.getItem();
        Instance instance = lookup.onItem(stack, Instance.class).orElse(null);
        if (instance == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        long used = (long) BuiltinPdc.integer(stack, USED_KEY) + 1;
        if (used >= instance.max()) {
            BuiltinPdc.clear(stack, USED_KEY, MAX_KEY);
            stack.setAmount(stack.getAmount() - 1);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        } else {
            stack.editMeta(meta -> {
                meta.getPersistentDataContainer().set(
                        BuiltinPdc.key(USED_KEY), PersistentDataType.INTEGER, (int) used);
                meta.getPersistentDataContainer().set(
                        BuiltinPdc.key(MAX_KEY), PersistentDataType.INTEGER, instance.max());
            });
        }
    }
}
