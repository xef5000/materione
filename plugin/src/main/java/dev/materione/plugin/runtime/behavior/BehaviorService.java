package dev.materione.plugin.runtime.behavior;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Facets;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.platform.items.MaterioneStacks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.inventory.ItemStack;

/**
 * The platform bridge over the ECA engine: resolves a fired Bukkit event to
 * the involved item's rules and drives them through the engine. Context
 * trees ({@code player.*}, {@code item.*}, {@code location.*}, {@code
 * server.*}, mutable {@code vars}) are built per event; rules resolve
 * against the live registry snapshot at event entry (shadow-swap safe —
 * an in-flight event completes against the snapshot it started with).
 */
public final class BehaviorService {

    private final java.util.function.Supplier<RuntimeRegistries> registries;
    private final RuleEngine engine;

    /**
     * @param engine the rule engine of the logic service: gating, the resumable interpreter and the
     *        thread gateway behind it (logic.md §5)
     */
    public BehaviorService(RuleEngine engine, dev.materione.core.runtime.ContentRuntime runtime) {
        // resolved per event, never captured: reloads swap the reference
        this.registries = runtime::registries;
        this.engine = engine;
    }

    /**
     * Fires {@code eventType}/{@code detail} for the given player and held
     * stack. Applies cancellation after the chain when an action requested it.
     */
    public void fireItemEvent(
            String eventType, String detail, Player player, ItemStack stack, Cancellable cancellable) {
        List<dev.materione.behavior.rule.Rule> rules = rulesFor(stack);
        if (rules.isEmpty()) {
            return;
        }
        BehaviorEvent event = new BehaviorEvent(eventType, detail, buildContext(player, stack), cancellable, player);
        engine.fire(rules, event);
        if (event.isCancelRequested() && cancellable != null) {
            cancellable.setCancelled(true);
        }
    }

    /**
     * Fires {@code eventType}/{@code detail} for a custom block at {@code
     * location} — place/break/interact from the world runtime. Rules resolve
     * from the live shadow at event entry; cancellation applies after the
     * chain. Player-less causes (explosions, pistons) fire no events.
     */
    public void fireBlockEvent(
            String eventType,
            String detail,
            Player player,
            Location location,
            NamespacedId blockId,
            String techniqueName,
            Cancellable cancellable) {
        BlockBehaviors behaviors = registries.get().view(BlockBehaviors.KEY);
        if (behaviors == null) {
            return;
        }
        List<dev.materione.behavior.rule.Rule> rules = behaviors.forBlock(blockId).orElse(List.of());
        if (rules.isEmpty()) {
            return;
        }
        BehaviorEvent event = new BehaviorEvent(
                eventType, detail, buildBlockContext(player, location, blockId, techniqueName),
                cancellable, player);
        engine.fire(rules, event);
        if (event.isCancelRequested() && cancellable != null) {
            cancellable.setCancelled(true);
        }
    }

    /**
     * Fires {@code eventType}/{@code detail} for a furniture cluster —
     * place/click from the cluster runtime. Same resolution and cancellation
     * contract as block events. Returns whether a rule requested
     * cancellation — the caller's "an ECA rule consumed this event" signal
     * (the cancellable may have been cancelled before the fire, so the
     * Bukkit flag cannot carry it).
     */
    public boolean fireFurnitureEvent(
            String eventType,
            String detail,
            Player player,
            Location location,
            NamespacedId furnitureId,
            Cancellable cancellable) {
        FurnitureBehaviors behaviors = registries.get().view(FurnitureBehaviors.KEY);
        if (behaviors == null) {
            return false;
        }
        List<dev.materione.behavior.rule.Rule> rules = behaviors.forFurniture(furnitureId).orElse(List.of());
        if (rules.isEmpty()) {
            return false;
        }
        var root = new LinkedHashMap<String, Object>();
        if (player != null) {
            var playerVars = new LinkedHashMap<String, Object>();
            playerVars.put("name", player.getName());
            playerVars.put("uuid", player.getUniqueId().toString());
            playerVars.put("gamemode", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
            root.put("player", playerVars);
        }
        World world = location.getWorld();
        var locationVars = new LinkedHashMap<String, Object>();
        locationVars.put("x", location.getBlockX());
        locationVars.put("y", location.getBlockY());
        locationVars.put("z", location.getBlockZ());
        locationVars.put("world", world == null ? "" : world.getName());
        root.put("location", locationVars);
        var furnitureVars = new LinkedHashMap<String, Object>();
        furnitureVars.put("id", furnitureId.toString());
        root.put("furniture", furnitureVars);
        root.put("vars", new LinkedHashMap<String, Object>());
        BehaviorEvent event = new BehaviorEvent(eventType, detail, logicContext(root, player), cancellable, player);
        engine.fire(rules, event);
        boolean cancelRequested = event.isCancelRequested();
        if (cancelRequested && cancellable != null) {
            cancellable.setCancelled(true);
        }
        return cancelRequested;
    }

    /** Rules attached to the stack's Materione identity, resolved at event entry. */
    private List<dev.materione.behavior.rule.Rule> rulesFor(ItemStack stack) {
        Optional<NamespacedId> identity = MaterioneStacks.identityOf(stack);
        if (identity.isEmpty()) {
            return List.of();
        }
        ItemBehaviors behaviors = registries.get().view(ItemBehaviors.KEY);
        return behaviors == null ? List.of() : behaviors.forItem(identity.get()).orElse(List.of());
    }

    // --- context construction --------------------------------------------------

    /**
     * The expression-visible variable tree for one event: nested maps under
     * {@code player/item/location/server/vars}. {@code vars} is the single
     * mutable namespace ({@code set_var} writes there).
     */
    private MapLogicContext buildContext(Player player, ItemStack stack) {
        var root = new LinkedHashMap<String, Object>();
        Location location = player.getLocation();
        World world = location.getWorld();

        var playerVars = new LinkedHashMap<String, Object>();
        playerVars.put("name", player.getName());
        playerVars.put("uuid", player.getUniqueId().toString());
        playerVars.put("health", player.getHealth());
        playerVars.put("level", player.getLevel());
        playerVars.put("gamemode", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
        playerVars.put("sneaking", player.isSneaking());
        playerVars.put("op", player.isOp());
        playerVars.put("x", round(location.getX()));
        playerVars.put("y", round(location.getY()));
        playerVars.put("z", round(location.getZ()));
        playerVars.put("yaw", round(location.getYaw()));
        playerVars.put("pitch", round(location.getPitch()));
        root.put("player", playerVars);

        var locationVars = new LinkedHashMap<String, Object>();
        locationVars.put("x", round(location.getX()));
        locationVars.put("y", round(location.getY()));
        locationVars.put("z", round(location.getZ()));
        locationVars.put("world", world == null ? "" : world.getName());
        root.put("location", locationVars);

        var worldVars = new LinkedHashMap<String, Object>();
        worldVars.put("name", world == null ? "" : world.getName());
        worldVars.put("time", world == null ? 0L : world.getTime());
        root.put("world", worldVars);

        root.put("item", itemContext(stack));

        var serverVars = new LinkedHashMap<String, Object>();
        serverVars.put("online", Bukkit.getOnlinePlayers().size());
        serverVars.put("max", Bukkit.getMaxPlayers());
        root.put("server", serverVars);

        root.put("vars", new LinkedHashMap<String, Object>());
        return logicContext(root, player);
    }

    /**
     * The run's context: the variable tree plus what only the platform can supply, the player
     * facet and PlaceholderAPI resolution for that player.
     */
    private static MapLogicContext logicContext(Map<String, Object> root, Player player) {
        return new MapLogicContext(root)
                .with(Facets.PLAYER, player)
                .withPlaceholders(text -> PlaceholderResolver.apply(player, text));
    }

    private static Map<String, Object> itemContext(ItemStack stack) {
        var itemVars = new LinkedHashMap<String, Object>();
        Optional<NamespacedId> identity = MaterioneStacks.identityOf(stack);
        itemVars.put("id", identity.map(NamespacedId::toString).orElse(""));
        itemVars.put("material", stack == null ? "" : stack.getType().name().toLowerCase(java.util.Locale.ROOT));
        itemVars.put("amount", stack == null ? 0 : stack.getAmount());
        if (stack != null && stack.getItemMeta() != null) {
            addDurabilityContext(itemVars, stack.getItemMeta().getPersistentDataContainer());
        }
        return itemVars;
    }

    static void addDurabilityContext(
            Map<String, Object> itemVars, org.bukkit.persistence.PersistentDataContainer pdc) {
        Integer used = pdc.get(new org.bukkit.NamespacedKey("materione", "durability-used"),
                org.bukkit.persistence.PersistentDataType.INTEGER);
        Integer max = pdc.get(new org.bukkit.NamespacedKey("materione", "durability-max"),
                org.bukkit.persistence.PersistentDataType.INTEGER);
        if (used != null && max != null) {
            itemVars.put("durability_used", used);
            itemVars.put("durability_max", max);
        }
    }

    /**
     * The block-event tree: {@code player.*} only when a player caused the
     * event (explosions and pistons fire no events), plus
     * {@code location/world/block/vars}.
     */
    private MapLogicContext buildBlockContext(
            Player player, Location location, NamespacedId blockId, String techniqueName) {
        var root = new LinkedHashMap<String, Object>();
        if (player != null) {
            var playerVars = new LinkedHashMap<String, Object>();
            playerVars.put("name", player.getName());
            playerVars.put("uuid", player.getUniqueId().toString());
            playerVars.put("gamemode", player.getGameMode().name().toLowerCase(java.util.Locale.ROOT));
            playerVars.put("sneaking", player.isSneaking());
            playerVars.put("op", player.isOp());
            root.put("player", playerVars);
        }

        World world = location.getWorld();
        var locationVars = new LinkedHashMap<String, Object>();
        locationVars.put("x", location.getBlockX());
        locationVars.put("y", location.getBlockY());
        locationVars.put("z", location.getBlockZ());
        locationVars.put("world", world == null ? "" : world.getName());
        root.put("location", locationVars);

        var worldVars = new LinkedHashMap<String, Object>();
        worldVars.put("name", world == null ? "" : world.getName());
        worldVars.put("time", world == null ? 0L : world.getTime());
        root.put("world", worldVars);

        var blockVars = new LinkedHashMap<String, Object>();
        blockVars.put("id", blockId.toString());
        blockVars.put("technique", techniqueName);
        root.put("block", blockVars);

        var serverVars = new LinkedHashMap<String, Object>();
        serverVars.put("online", Bukkit.getOnlinePlayers().size());
        serverVars.put("max", Bukkit.getMaxPlayers());
        root.put("server", serverVars);

        root.put("vars", new LinkedHashMap<String, Object>());
        return logicContext(root, player);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
