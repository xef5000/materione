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
import dev.materione.platform.items.ItemListCodec;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

/** Right-click opens the container stored in that particular stack's PDC. */
public final class BackpackMechanic implements Mechanic {

    private static final String ID_KEY = "backpack-id";
    private static final String DATA_KEY = "backpack-data";

    public record Instance(int rows, String title) implements MechanicInstance {}

    @Override
    public String name() {
        return "backpack";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Backpack",
                "A container that travels inside the item",
                List.of(
                        new ParameterSchema("rows", ParameterType.INTEGER, true, null,
                                "Inventory rows (1..6)", null, null),
                        new ParameterSchema("title", ParameterType.STRING, false, "Backpack",
                                "Inventory title", null, null)),
                "2.0.0",
                "Right-clicking with the item opens its storage; contents save when the inventory closes");
    }

    @Override
    public Instance parse(MechanicConfiguration configuration) {
        return new Instance(
                configuration.integer("rows", -1, 1, 6), configuration.text("title", "Backpack"));
    }

    @Override
    public MechanicRuntime createRuntime(MechanicContext context) {
        return new Runtime();
    }

    private static final class Runtime implements MechanicRuntime {
        @Override
        public List<MechanicListener<?>> listeners() {
            return List.of(
                    new MechanicListener<>(PlayerInteractEvent.class, EventPriority.HIGH, false,
                            ThreadClass.REGION, this::onInteract),
                    new MechanicListener<>(InventoryCloseEvent.class, EventPriority.MONITOR, false,
                            ThreadClass.REGION, this::onClose));
        }

        private void onInteract(PlayerInteractEvent event, MechanicLookup lookup) {
            if (event.getHand() != EquipmentSlot.HAND
                    || (event.getAction() != Action.RIGHT_CLICK_AIR
                            && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
                return;
            }
            Instance instance = lookup.onItem(event.getItem(), Instance.class).orElse(null);
            if (instance == null) {
                return;
            }
            event.setCancelled(true);
            open(event.getPlayer(), instance);
        }

        private void open(Player player, Instance instance) {
            ItemStack held = player.getInventory().getItemInMainHand();
            String instanceId = BuiltinPdc.string(held, ID_KEY);
            if (instanceId == null) {
                String fresh = UUID.randomUUID().toString();
                instanceId = fresh;
                held.editMeta(meta -> meta.getPersistentDataContainer().set(
                        BuiltinPdc.key(ID_KEY), PersistentDataType.STRING, fresh));
            }
            Inventory menu = Bukkit.createInventory(
                    new BackpackHolder(this, instanceId), instance.rows() * 9,
                    LegacyComponentSerializer.legacyAmpersand().deserialize(instance.title()));
            ItemStack[] contents = new ItemStack[instance.rows() * 9];
            List<ItemStack> stored = ItemListCodec.decode(BuiltinPdc.bytes(held, DATA_KEY));
            for (int i = 0; i < contents.length && i < stored.size(); i++) {
                contents[i] = stored.get(i);
            }
            menu.setContents(contents);
            player.openInventory(menu);
        }

        private void onClose(InventoryCloseEvent event, MechanicLookup lookup) {
            if (!(event.getInventory().getHolder() instanceof BackpackHolder holder)
                    || holder.owner() != this || !(event.getPlayer() instanceof Player player)) {
                return;
            }
            ItemStack stack = findStack(player, holder.instanceId());
            if (stack == null) {
                return;
            }
            byte[] encoded = ItemListCodec.encode(Arrays.asList(event.getInventory().getContents()));
            stack.editMeta(meta -> meta.getPersistentDataContainer().set(
                    BuiltinPdc.key(DATA_KEY), PersistentDataType.BYTE_ARRAY, encoded));
        }
    }

    private record BackpackHolder(Runtime owner, String instanceId) implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            throw new IllegalStateException("holder has no inventory of its own");
        }
    }

    private static ItemStack findStack(Player player, String instanceId) {
        PlayerInventory inventory = player.getInventory();
        for (ItemStack candidate : inventory.getStorageContents()) {
            if (instanceId.equals(BuiltinPdc.string(candidate, ID_KEY))) {
                return candidate;
            }
        }
        ItemStack offHand = inventory.getItemInOffHand();
        return instanceId.equals(BuiltinPdc.string(offHand, ID_KEY)) ? offHand : null;
    }
}
