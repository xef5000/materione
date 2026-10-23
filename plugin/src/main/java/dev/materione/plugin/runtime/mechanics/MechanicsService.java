package dev.materione.plugin.runtime.mechanics;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.mechanics.Mechanic;
import dev.materione.api.registry.Registry;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.ItemListCodec;
import dev.materione.platform.items.MaterioneStacks;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * The M2 native mechanics runtime ({@link NativeMechanics} carry identity
 * and self-description; this class carries the wiring). Listeners are
 * registered once at boot and resolve the applied shadow at event entry —
 * reloads swap views atomically, so no register/unregister churn is needed.
 * All inventory/effect mutation goes through the scheduling gateway (R1).
 */
public final class MechanicsService implements Listener {

    /** Player-PDC key holding the soulbound stash while the player is dead. */
    private static final String SOULBOUND_KEY = "soulbound";
    /** Stack-PDC key giving each backpack instance a stable identity. */
    private static final String BACKPACK_ID_KEY = "backpack-id";
    /** Stack-PDC key holding the backpack contents. */
    private static final String BACKPACK_DATA_KEY = "backpack-data";
    /** Stack-PDC keys of the custom durability counter (written together). */
    private static final String DURABILITY_USED_KEY = "durability-used";
    private static final String DURABILITY_MAX_KEY = "durability-max";

    private static final int DEFAULT_FOOD_DURATION_TICKS = 200;

    private final Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;
    private final Supplier<RuntimeRegistries> registries;
    private final AtomicLong lastRuntimeWarningNanos = new AtomicLong();

    public MechanicsService(
            Plugin plugin,
            DiagnosticsBus diagnostics,
            SchedulingGateway scheduling,
            dev.materione.core.runtime.ContentRuntime runtime) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        // resolved per event, never captured: reloads swap the reference
        this.registries = runtime::registries;
    }

    /** Registers the five built-ins through the registry path (D5) — phase 4, before content load. */
    public void registerNatives(dev.materione.core.registry.RegistryService registryService) {
        Registry<Mechanic> registry =
                registryService.registry(MechanicsResolver.REGISTRY_KEY, Mechanic.class);
        for (Mechanic mechanic : NativeMechanics.all()) {
            registry.register(
                    NamespacedId.of("materione", mechanic.name()),
                    mechanic,
                    mechanic.describe(),
                    "materione");
        }
    }

    private ItemMechanics mechanics() {
        return registries.get().view(ItemMechanics.KEY);
    }

    // --- commands + backpack: item clicks -----------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // main hand owns mechanics; the off-hand pass is a duplicate
        }
        boolean right = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left = event.getAction() == Action.LEFT_CLICK_AIR
                || event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) {
            return;
        }
        ItemStack stack = event.getItem();
        NamespacedId id = MaterioneStacks.identityOf(stack).orElse(null);
        if (id == null) {
            return;
        }
        List<ActiveMechanic> active = mechanics().forItem(id);
        if (active.isEmpty()) {
            return;
        }
        for (ActiveMechanic mechanic : active) {
            switch (mechanic.name()) {
                case "commands" -> runCommands(mechanic, event.getPlayer(), right);
                case "backpack" -> {
                    if (right) {
                        event.setCancelled(true); // never place/use through an open attempt
                        openBackpack(mechanic, event.getPlayer());
                    }
                }
                default -> { }
            }
        }
    }

    private void runCommands(ActiveMechanic mechanic, Player player, boolean rightClick) {
        Object value = mechanic.params().get(rightClick ? "on_right_click" : "on_left_click");
        if (!(value instanceof List<?> commands)) {
            return;
        }
        boolean asPlayer = ActiveMechanic.boolParam(mechanic.params(), "as_player", false);
        for (Object entry : commands) {
            if (!(entry instanceof String command) || command.isBlank()) {
                continue;
            }
            String filled = command.replace("%player%", player.getName());
            scheduling.executeEntity(plugin, player, () -> {
                CommandSender sender = asPlayer ? player : Bukkit.getConsoleSender();
                Bukkit.dispatchCommand(sender, filled);
            });
        }
    }

    private void openBackpack(ActiveMechanic mechanic, Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack held = inventory.getItemInMainHand();
        String instanceId = pdcString(held, BACKPACK_ID_KEY);
        if (instanceId == null) {
            String fresh = UUID.randomUUID().toString();
            instanceId = fresh;
            held.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(key(BACKPACK_ID_KEY), PersistentDataType.STRING, fresh));
        }
        int rows = Math.min(6, Math.max(1, ActiveMechanic.intParam(mechanic.params(), "rows", 3)));
        String title = ActiveMechanic.textParam(mechanic.params(), "title", "Backpack");
        Inventory menu = Bukkit.createInventory(
                new BackpackHolder(instanceId),
                rows * 9,
                LegacyComponentSerializer.legacyAmpersand().deserialize(title));
        ItemStack[] contents = new ItemStack[rows * 9];
        List<ItemStack> stored = ItemListCodec.decode(pdcBytes(held, BACKPACK_DATA_KEY));
        for (int i = 0; i < contents.length && i < stored.size(); i++) {
            contents[i] = stored.get(i);
        }
        menu.setContents(contents);
        player.openInventory(menu);
    }

    /** Writes backpack contents back into the stack that owns them. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof BackpackHolder holder)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack stack = findBackpackStack(player, holder.instanceId());
        if (stack == null) {
            return; // dropped or moved while open: contents keep their old state
        }
        byte[] encoded = ItemListCodec.encode(Arrays.asList(event.getInventory().getContents()));
        stack.editMeta(meta -> meta.getPersistentDataContainer()
                .set(key(BACKPACK_DATA_KEY), PersistentDataType.BYTE_ARRAY, encoded));
    }

    /** The holder records which backpack instance an open inventory belongs to. */
    private record BackpackHolder(String instanceId)
            implements org.bukkit.inventory.InventoryHolder {
        @Override
        public Inventory getInventory() {
            throw new IllegalStateException("holder has no inventory of its own");
        }
    }

    private ItemStack findBackpackStack(Player player, String instanceId) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (ItemStack candidate : storage) {
            if (candidate != null && instanceId.equals(pdcString(candidate, BACKPACK_ID_KEY))) {
                return candidate;
            }
        }
        ItemStack offHand = inventory.getItemInOffHand();
        return offHand != null && instanceId.equals(pdcString(offHand, BACKPACK_ID_KEY))
                ? offHand : null;
    }

    // --- soulbound -----------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        var stash = new ArrayList<ItemStack>();
        Iterator<ItemStack> drops = event.getDrops().iterator();
        while (drops.hasNext()) {
            ItemStack drop = drops.next();
            NamespacedId id = MaterioneStacks.identityOf(drop).orElse(null);
            if (id != null && mechanics().onItem(id, "soulbound").isPresent()) {
                stash.add(drop);
                drops.remove();
            }
        }
        if (stash.isEmpty()) {
            return;
        }
        List<ItemStack> combined = ItemListCodec.decode(
                player.getPersistentDataContainer()
                        .get(key(SOULBOUND_KEY), PersistentDataType.BYTE_ARRAY));
        combined.addAll(stash);
        player.getPersistentDataContainer().set(
                key(SOULBOUND_KEY), PersistentDataType.BYTE_ARRAY, ItemListCodec.encode(combined));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        restoreStash(event.getPlayer());
    }

    /** Join-time fallback: covers respawn interference or a crash between death and respawn. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        restoreStash(event.getPlayer());
    }

    private void restoreStash(Player player) {
        byte[] raw = player.getPersistentDataContainer()
                .get(key(SOULBOUND_KEY), PersistentDataType.BYTE_ARRAY);
        if (raw == null || raw.length == 0) {
            return;
        }
        player.getPersistentDataContainer()
                .set(key(SOULBOUND_KEY), PersistentDataType.BYTE_ARRAY, new byte[0]);
        List<ItemStack> stashed = ItemListCodec.decode(raw);
        scheduling.executeEntity(plugin, player, () -> {
            player.getInventory().addItem(stashed.toArray(ItemStack[]::new)).values()
                    .forEach(leftover -> player.getWorld()
                            .dropItemNaturally(player.getLocation(), leftover));
        });
    }

    // --- durability ----------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack stack = event.getItem();
        NamespacedId id = MaterioneStacks.identityOf(stack).orElse(null);
        if (id == null) {
            return;
        }
        ActiveMechanic durability = mechanics().onItem(id, "durability").orElse(null);
        if (durability == null) {
            return;
        }
        int max = ActiveMechanic.intParam(durability.params(), "max", 0);
        event.setCancelled(true); // the vanilla damage bar is inert; our counter owns wear

        Player player = event.getPlayer();
        int used = pdcInt(stack, DURABILITY_USED_KEY) + 1;
        if (used >= max) {
            clearPdc(stack, DURABILITY_USED_KEY, DURABILITY_MAX_KEY);
            stack.setAmount(stack.getAmount() - 1);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        } else {
            writeDurability(stack, used, max);
        }
    }

    private static void writeDurability(ItemStack stack, int used, int max) {
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer()
                    .set(key(DURABILITY_USED_KEY), PersistentDataType.INTEGER, used);
            meta.getPersistentDataContainer()
                    .set(key(DURABILITY_MAX_KEY), PersistentDataType.INTEGER, max);
        });
    }

    // --- food ----------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        NamespacedId id = MaterioneStacks.identityOf(event.getItem()).orElse(null);
        if (id == null) {
            return;
        }
        ActiveMechanic food = mechanics().onItem(id, "food").orElse(null);
        if (food == null) {
            return;
        }
        Object effects = food.params().get("effects");
        if (!(effects instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        scheduling.executeEntity(plugin, player, () -> applyEffects(player, list));
    }

    private void applyEffects(Player player, List<?> effects) {
        for (Object entry : effects) {
            if (!(entry instanceof Map<?, ?> effect)) {
                continue;
            }
            String type = effect.get("type") instanceof String text ? text : null;
            PotionEffectType potionType = potionType(type);
            if (potionType == null) {
                warnLimited("food effect type '" + type + "' is not a potion type");
                continue;
            }
            int amplifier = effect.get("amplifier") instanceof Number number
                    ? number.intValue() : 0;
            int duration = effect.get("duration_ticks") instanceof Number number
                    ? number.intValue() : DEFAULT_FOOD_DURATION_TICKS;
            player.addPotionEffect(new PotionEffect(potionType, duration, amplifier));
        }
    }

    // --- helpers -------------------------------------------------------------

    /** Registry lookup (the deprecated getByName path); invalid keys read as unknown. */
    private static PotionEffectType potionType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return org.bukkit.Registry.POTION_EFFECT_TYPE.get(
                    org.bukkit.NamespacedKey.minecraft(type.toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return null; // characters a NamespacedKey can't carry
        }
    }

    private static org.bukkit.NamespacedKey key(String name) {
        return new org.bukkit.NamespacedKey("materione", name);
    }

    private static String pdcString(ItemStack stack, String name) {
        return stack.getItemMeta() == null
                ? null : stack.getItemMeta().getPersistentDataContainer()
                        .get(key(name), PersistentDataType.STRING);
    }

    private static byte[] pdcBytes(ItemStack stack, String name) {
        return stack.getItemMeta() == null
                ? null : stack.getItemMeta().getPersistentDataContainer()
                        .get(key(name), PersistentDataType.BYTE_ARRAY);
    }

    private static int pdcInt(ItemStack stack, String name) {
        Integer value = stack.getItemMeta() == null
                ? null : stack.getItemMeta().getPersistentDataContainer()
                        .get(key(name), PersistentDataType.INTEGER);
        return value == null ? 0 : value;
    }

    private static void clearPdc(ItemStack stack, String... names) {
        stack.editMeta(meta -> {
            for (String name : names) {
                meta.getPersistentDataContainer().remove(key(name));
            }
        });
    }

    /** Runtime mechanic failures are rate-limited WARNs on the action-failure code. */
    private void warnLimited(String message) {
        long now = System.nanoTime();
        long last = lastRuntimeWarningNanos.get();
        if (now - last < 1_000_000_000L || !lastRuntimeWarningNanos.compareAndSet(last, now)) {
            return;
        }
        diagnostics.publish(new Diagnostic(
                DiagnosticCodes.ACTION_FAILED, Severity.WARN, message, null, null,
                "repeated occurrences are suppressed for one second"));
    }
}
