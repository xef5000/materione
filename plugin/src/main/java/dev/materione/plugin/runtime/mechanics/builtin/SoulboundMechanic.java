package dev.materione.plugin.runtime.mechanics.builtin;

import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.mechanics.MechanicConfiguration;
import dev.materione.api.mechanics.MechanicContext;
import dev.materione.api.mechanics.MechanicInstance;
import dev.materione.api.mechanics.MechanicListener;
import dev.materione.api.mechanics.MechanicListener.ThreadClass;
import dev.materione.api.mechanics.MechanicLookup;
import dev.materione.api.mechanics.MechanicRuntime;
import dev.materione.api.registry.SelfDescription;
import dev.materione.platform.items.ItemListCodec;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Death stashes are durable player PDC, not a runtime-only inventory. */
public final class SoulboundMechanic implements Mechanic {

    private static final String STASH_KEY = "soulbound";

    public record Instance() implements MechanicInstance {}

    @Override
    public String name() {
        return "soulbound";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Soulbound", "The item stays with its owner through death", List.of(), "2.0.0");
    }

    @Override
    public Instance parse(MechanicConfiguration configuration) {
        return new Instance();
    }

    @Override
    public MechanicRuntime createRuntime(MechanicContext context) {
        return new Runtime(context);
    }

    private static final class Runtime implements MechanicRuntime {
        private final MechanicContext context;

        private Runtime(MechanicContext context) {
            this.context = context;
        }

        @Override
        public List<MechanicListener<?>> listeners() {
            return List.of(
                    new MechanicListener<>(PlayerDeathEvent.class, EventPriority.HIGH, false,
                            ThreadClass.REGION, this::onDeath),
                    new MechanicListener<>(PlayerRespawnEvent.class, EventPriority.MONITOR, false,
                            ThreadClass.REGION, (event, lookup) -> restore(event.getPlayer())),
                    new MechanicListener<>(PlayerJoinEvent.class, EventPriority.MONITOR, false,
                            ThreadClass.REGION, (event, lookup) -> restore(event.getPlayer())));
        }

        private void onDeath(PlayerDeathEvent event, MechanicLookup lookup) {
            if (event.getKeepInventory()) {
                return; // the original stacks already survive; stashing would duplicate them
            }
            List<ItemStack> stash = event.getDrops().stream()
                    .filter(drop -> lookup.onItem(drop, Instance.class).isPresent())
                    .toList();
            if (stash.isEmpty()) {
                return;
            }
            Player player = event.getEntity();
            var combined = new ArrayList<>(ItemListCodec.decode(player.getPersistentDataContainer()
                    .get(BuiltinPdc.key(STASH_KEY), PersistentDataType.BYTE_ARRAY)));
            combined.addAll(stash);
            byte[] encoded = ItemListCodec.encode(combined);
            player.getPersistentDataContainer().set(
                    BuiltinPdc.key(STASH_KEY), PersistentDataType.BYTE_ARRAY, encoded);
            event.getDrops().removeAll(stash);
        }

        private void restore(Player player) {
            context.scheduling().executeEntity(context.plugin(), player, () -> {
                byte[] raw = player.getPersistentDataContainer()
                        .get(BuiltinPdc.key(STASH_KEY), PersistentDataType.BYTE_ARRAY);
                if (raw == null || raw.length == 0) {
                    return;
                }
                ItemStack[] stashed = ItemListCodec.decode(raw).stream()
                        .filter(stack -> stack != null && !stack.getType().isAir())
                        .toArray(ItemStack[]::new);
                player.getInventory().addItem(stashed).values().forEach(leftover -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), leftover));
                player.getPersistentDataContainer().set(
                        BuiltinPdc.key(STASH_KEY), PersistentDataType.BYTE_ARRAY, new byte[0]);
            });
        }
    }
}
