package dev.materione.plugin.runtime.furniture;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.runtime.RuntimeFurniture;
import dev.materione.core.runtime.RuntimeFurnitures;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.runtime.behavior.BehaviorService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The furniture cluster runtime (runtime-systems §5, M2 slice 5). A cluster
 * is a persistent {@link ItemDisplay} root wearing the model plus member
 * entities ({@link Interaction} hitboxes, plain armor-stand seats,
 * {@link TextDisplay} labels) and tagged real blocks (barriers, LIGHT
 * blocks — waterlogged when they replace water). The root's entity PDC
 * carries the whole record (id, member uuids, cells, timestamps, storage),
 * so clusters survive restarts and rebuild missing members when their chunk
 * loads — reload swaps definitions and refreshes visuals only, never
 * rewriting a stored id.
 */
public final class FurnitureService implements Listener {

    private static final NamespacedKey KEY_FURNITURE = key("furniture-id");
    private static final NamespacedKey KEY_ROOT = key("furniture-root");
    private static final NamespacedKey KEY_MEMBERS = key("members");
    private static final NamespacedKey KEY_SEATS = key("seats");
    private static final NamespacedKey KEY_BARRIERS = key("barrier-cells");
    private static final NamespacedKey KEY_LIGHTS = key("light-cells");
    private static final NamespacedKey KEY_PLACED = key("placed-at");
    private static final NamespacedKey KEY_STAGE = key("stage");
    private static final NamespacedKey KEY_STORAGE = key("storage");

    private final org.bukkit.plugin.Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;
    private final dev.materione.core.runtime.ContentRuntime runtime;
    private final BehaviorService behavior;

    /** Rate limiting for world diagnostics (MTR-0026). */
    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();

    public FurnitureService(
            org.bukkit.plugin.Plugin plugin,
            DiagnosticsBus diagnostics,
            SchedulingGateway scheduling,
            dev.materione.core.runtime.ContentRuntime runtime,
            BehaviorService behavior) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        this.runtime = runtime;
        this.behavior = behavior;
    }

    private static NamespacedKey key(String name) {
        return NamespacedKey.fromString("materione:" + name);
    }

    /**
     * Whether {@code block} is one of any cluster's claimed cells (anchor,
     * barrier or light). Other runtimes check it before acting on a click so
     * furniture keeps its interaction semantics.
     */
    public static boolean isFurnitureCell(Block block) {
        return block.getChunk().getPersistentDataContainer()
                .has(cellKey(block.getX(), block.getY(), block.getZ()), PersistentDataType.STRING);
    }

    private RuntimeFurnitures furnitures() {
        RuntimeRegistries registries = runtime.registries();
        return registries == null ? null : registries.view(RuntimeFurnitures.KEY);
    }

    // --- placement -------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getBlockFace() != BlockFace.UP) {
            return; // floor-only placing (§5)
        }
        ItemStack held = event.getItem();
        Optional<NamespacedId> itemId = dev.materione.platform.items.MaterioneStacks.identityOf(held);
        if (itemId.isEmpty()) {
            return;
        }
        RuntimeFurnitures furnitures = furnitures();
        if (furnitures == null) {
            return;
        }
        Optional<NamespacedId> furnitureId = furnitures.placedBy(itemId.get());
        if (furnitureId.isEmpty()) {
            return;
        }
        Optional<RuntimeFurniture> def = furnitures.byId(furnitureId.get());
        if (def.isEmpty()) {
            diagnostics.publish(Diagnostic.warn(DiagnosticCodes.ORPHANED_PLACEMENT,
                    "item " + itemId.get() + " places unknown furniture " + furnitureId.get()));
            return;
        }
        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        Block anchor = clicked.getRelative(BlockFace.UP);
        float yaw = FurnitureMath.snapYaw(def.get(), event.getPlayer().getYaw());
        if (!spaceOk(anchor, def.get(), yaw)) {
            event.getPlayer().sendMessage(Component.text(
                    "Not enough space for " + displayName(def.get()), NamedTextColor.RED));
            return;
        }
        spawnCluster(anchor.getWorld(), anchor.getX(), anchor.getY(), anchor.getZ(), def.get(), yaw);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1); // interact events do not consume
        }
        behavior.fireFurnitureEvent(dev.materione.behavior.rule.EventTypes.PLACE, null,
                event.getPlayer(), anchor.getLocation(), def.get().id(), null);
    }

    /**
     * Rotation-aware space pre-check (adopt #12): every claimed cell must be
     * replaceable and free of other furniture records.
     */
    private boolean spaceOk(Block anchor, RuntimeFurniture def, float yaw) {
        for (RuntimeFurniture.Hitbox hitbox : def.hitboxes()) {
            if (!hitbox.isBarrier()) {
                continue;
            }
            int[] cell = FurnitureMath.cell(
                    anchor.getX(), anchor.getY(), anchor.getZ(), hitbox.offset(), yaw);
            Block block = anchor.getWorld().getBlockAt(cell[0], cell[1], cell[2]);
            if (!block.isReplaceable() || rootUuidAt(block).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** Spawns the whole cluster and records it in the root's entity PDC. */
    public void spawnCluster(World world, int x, int y, int z, RuntimeFurniture def, float yaw) {
        // the anchor cell spans [y, y+1]; displays render their model centered
        // on the entity position in every axis, so the root sits at the block
        // center to fill the cell
        Location origin = new Location(world, x + 0.5, y + 0.5, z + 0.5);

        ItemDisplay root = world.spawn(origin, ItemDisplay.class, display -> {
            display.setItemStack(displayStack(def));
            display.setRotation(yaw, 0f);
            // the renderer works from the transformation matrix, not body
            // yaw (issue #77): rotationY carries the same component formula
            // as FurnitureMath.rotate, so the model turns with the cells
            display.setTransformation(new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(),
                    new org.joml.Quaternionf().rotationY((float) Math.toRadians(yaw)),
                    new org.joml.Vector3f(1, 1, 1),
                    new org.joml.Quaternionf()));
        });
        root.getPersistentDataContainer().set(KEY_FURNITURE, PersistentDataType.STRING, def.id().toString());
        root.getPersistentDataContainer().set(KEY_PLACED, PersistentDataType.LONG, System.currentTimeMillis());
        root.getPersistentDataContainer().set(KEY_STAGE, PersistentDataType.INTEGER, 0);
        root.setPersistent(true);
        // the anchor cell carries the cluster pointer too: /materione dev
        // remove and the space pre-check resolve clusters through it
        tagCell(world.getBlockAt(x, y, z), root.getUniqueId());

        var members = new ArrayList<UUID>();
        var seats = new ArrayList<UUID>();
        var barriers = new ArrayList<int[]>();
        var lights = new ArrayList<int[]>();

        for (RuntimeFurniture.Hitbox hitbox : def.hitboxes()) {
            double[] rotated = FurnitureMath.rotate(hitbox.offset(), yaw);
            if (hitbox.isBarrier()) {
                int[] cell = FurnitureMath.cell(x, y, z, hitbox.offset(), yaw);
                claimBarrierCell(world, cell, root.getUniqueId());
                barriers.add(cell);
            } else {
                Location at = new Location(world, x + 0.5 + rotated[0],
                        y + rotated[1], z + 0.5 + rotated[2]);
                Interaction interaction = world.spawn(at, Interaction.class, entity -> {
                    entity.setInteractionWidth((float) hitbox.width());
                    entity.setInteractionHeight((float) hitbox.height());
                    entity.setResponsive(true);
                });
                tagMember(interaction, root.getUniqueId());
                members.add(interaction.getUniqueId());
            }
        }
        for (double[] seatOffset : def.seats()) {
            double[] pos = FurnitureMath.seatPosition(x, y, z, seatOffset, yaw);
            ArmorStand seat = world.spawn(new Location(world, pos[0], pos[1], pos[2]), ArmorStand.class,
                    stand -> {
                        stand.setVisible(false);
                        stand.setMarker(true);
                        stand.setSmall(true);
                        stand.setGravity(false);
                        stand.setInvulnerable(true);
                    });
            tagMember(seat, root.getUniqueId());
            seats.add(seat.getUniqueId());
            members.add(seat.getUniqueId());
        }
        for (RuntimeFurniture.Light light : def.lights()) {
            int[] cell = FurnitureMath.cell(x, y, z, light.offset(), yaw);
            placeLightCell(world, cell, light.level(), root.getUniqueId());
            lights.add(cell);
        }
        if (def.label() != null) {
            // anchored to the cell (bottom at y + 1.25), not to the display
            // origin — it clears the model top by 0.25 either way
            TextDisplay label = world.spawn(new Location(world, x + 0.5, y + 1.25, z + 0.5),
                    TextDisplay.class, display -> {
                display.text(LegacyComponentSerializer.legacyAmpersand().deserialize(def.label()));
                display.setBillboard(Display.Billboard.CENTER);
            });
            tagMember(label, root.getUniqueId());
            members.add(label.getUniqueId());
        }

        var record = root.getPersistentDataContainer();
        record.set(KEY_MEMBERS, PersistentDataType.STRING, FurnitureMath.packUuids(members));
        record.set(KEY_SEATS, PersistentDataType.STRING, FurnitureMath.packUuids(seats));
        record.set(KEY_BARRIERS, PersistentDataType.STRING, FurnitureMath.packCells(barriers));
        record.set(KEY_LIGHTS, PersistentDataType.STRING, FurnitureMath.packCells(lights));
    }

    /** Barrier cells replace water (documented deviation: vanilla barriers cannot be waterlogged). */
    private void claimBarrierCell(World world, int[] cell, UUID root) {
        Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
        block.setType(Material.BARRIER, false);
        tagCell(block, root);
    }

    private void placeLightCell(World world, int[] cell, int level, UUID root) {
        Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
        boolean wasWater = block.isLiquid();
        block.setType(Material.LIGHT, false);
        if (block.getBlockData() instanceof Light light) {
            light.setLevel(Math.max(1, Math.min(15, level)));
            light.setWaterlogged(wasWater);
            block.setBlockData(light, false);
        }
        tagCell(block, root);
    }

    private void tagMember(Entity member, UUID root) {
        member.getPersistentDataContainer().set(KEY_ROOT, PersistentDataType.STRING, root.toString());
    }

    private void tagCell(Block block, UUID root) {
        block.getChunk().getPersistentDataContainer()
                .set(cellKey(block.getX(), block.getY(), block.getZ()), PersistentDataType.STRING,
                        root.toString());
    }

    private static NamespacedKey cellKey(int x, int y, int z) {
        return NamespacedKey.fromString("materione:furn-x" + (x & 15) + "y" + y + "z" + (z & 15));
    }

    private Optional<UUID> rootUuidAt(Block block) {
        String raw = block.getChunk().getPersistentDataContainer()
                .get(cellKey(block.getX(), block.getY(), block.getZ()), PersistentDataType.STRING);
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // --- interaction -----------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Entity target = event.getRightClicked();
        Optional<UUID> rootId = memberRoot(target);
        if (rootId.isEmpty()) {
            return;
        }
        event.setCancelled(true); // vanilla never interacts through a furniture member
        Entity root = nearbyEntity(target, rootId.get());
        if (root == null) {
            return;
        }
        Optional<RuntimeFurniture> def = definitionOf(root);
        if (def.isEmpty()) {
            return;
        }
        interact(event.getPlayer(), root, def.get(), event);
    }

    /**
     * Right-clicks on a cluster's barrier cells route to the furniture too:
     * the barrier is a real block, so the click targets the block (vanilla
     * no-op) wherever the cell occludes the Interaction entity's box —
     * for a full-cube cluster that is every click on the visible model.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onCellInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        Block clicked = event.getClickedBlock();
        String rootId = clicked.getChunk().getPersistentDataContainer()
                .get(cellKey(clicked.getX(), clicked.getY(), clicked.getZ()),
                        PersistentDataType.STRING);
        if (rootId == null) {
            return; // not a furniture cell
        }
        event.setCancelled(true);
        Entity root = nearbyEntityAround(clicked.getLocation(), UUID.fromString(rootId));
        if (root == null) {
            return;
        }
        Optional<RuntimeFurniture> def = definitionOf(root);
        if (def.isEmpty()) {
            return;
        }
        interact(event.getPlayer(), root, def.get(), event);
    }

    /**
     * The shared click flow: ECA rules may consume the click (reported as a
     * cancel request — the Bukkit flag is already set on both paths to block
     * the vanilla interaction), then storage, then seat.
     */
    private void interact(
            Player player, Entity root, RuntimeFurniture def, org.bukkit.event.Cancellable event) {
        boolean consumed = behavior.fireFurnitureEvent(
                dev.materione.behavior.rule.EventTypes.CLICK, "right",
                player, root.getLocation(), def.id(), event);
        if (consumed) {
            return; // an ECA rule consumed the click
        }
        if (def.storage() > 0) {
            openStorage(player, root, def);
            return;
        }
        sitPlayer(player, root, def);
    }

    /**
     * Safe dismount: vanilla pops the passenger somewhere near the seat —
     * if that lands inside a solid block (barrier hitboxes are solid),
     * nudge upward to a passable spot next tick.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (memberRoot(event.getDismounted()).isEmpty()) {
            return;
        }
        scheduling.executeEntity(plugin, player, () -> {
            Location at = player.getLocation();
            int tries = 0;
            while (!at.getBlock().isPassable() && tries < 3) {
                at.add(0, 1, 0);
                tries++;
            }
            if (tries > 0) {
                player.teleport(at);
            }
        });
    }

    /** Seats the player on the first live seat (plain entities + passenger API — no NMS). */
    private void sitPlayer(Player player, Entity root, RuntimeFurniture def) {
        if (def.seats().isEmpty()) {
            return;
        }
        List<UUID> seats = FurnitureMath.unpackUuids(
                root.getPersistentDataContainer().get(KEY_SEATS, PersistentDataType.STRING));
        for (UUID seatId : seats) {
            Entity seat = nearbyEntity(root, seatId);
            if (seat == null || !seat.isValid() || !seat.getPassengers().isEmpty()) {
                continue;
            }
            Location spot = seat.getLocation();
            scheduling.executeEntity(plugin, player, () -> {
                player.teleport(spot);
                seat.addPassenger(player);
            });
            return;
        }
    }

    // --- attack / removal --------------------------------------------------------

    /**
     * Displays are fragile: all damage to cluster entities cancels, and a
     * sneaking player breaks the cluster through the shared teardown.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        boolean rootHit = isRoot(event.getEntity());
        boolean memberHit = memberRoot(event.getEntity()).isPresent();
        if (!rootHit && !memberHit) {
            return;
        }
        event.setCancelled(true);
        if (!player.isSneaking()) {
            return;
        }
        Entity root = rootHit ? event.getEntity() : nearbyEntity(event.getEntity(),
                memberRoot(event.getEntity()).orElse(null));
        if (root != null) {
            removeCluster(root, true);
        }
    }

    /** Kills the cluster: despawn members, release cells, spill storage, drop the item. */
    public void removeCluster(Entity root, boolean drop) {
        Optional<RuntimeFurniture> def = definitionOf(root);
        var record = root.getPersistentDataContainer();

        List<UUID> members = FurnitureMath.unpackUuids(
                record.get(KEY_MEMBERS, PersistentDataType.STRING));
        for (UUID memberId : members) {
            Entity member = nearbyEntity(root, memberId);
            if (member != null) {
                member.remove();
            }
        }
        for (int[] cell : FurnitureMath.unpackCells(
                record.get(KEY_BARRIERS, PersistentDataType.STRING))) {
            releaseCell(root.getWorld(), cell, Material.AIR);
        }
        for (int[] cell : FurnitureMath.unpackCells(
                record.get(KEY_LIGHTS, PersistentDataType.STRING))) {
            releaseCell(root.getWorld(), cell, Material.AIR);
        }
        // release the anchor-cell pointer (the anchor block itself was never ours)
        Location center = root.getLocation();
        Block anchor = root.getWorld().getBlockAt(
                center.getBlockX(), center.getBlockY(), center.getBlockZ());
        anchor.getChunk().getPersistentDataContainer()
                .remove(cellKey(anchor.getX(), anchor.getY(), anchor.getZ()));
        if (drop) {
            for (ItemStack content : readStorage(record)) {
                if (content == null || content.getType().isAir()) {
                    continue; // empty grid slots ride the record as null holes
                }
                root.getWorld().dropItemNaturally(center, content);
            }
            Optional<RuntimeFurniture> dropped = definitionOf(root);
            if (dropped.isPresent() && furnitures() != null) {
                furnitures().itemFor(dropped.get().id()).ifPresent(itemId -> {
                    Optional<dev.materione.core.runtime.RuntimeItem> item =
                            runtime.registries().item(itemId);
                    item.ifPresent(value -> root.getWorld().dropItemNaturally(center,
                            dev.materione.platform.items.MaterioneStacks.build(
                                    new dev.materione.platform.items.MaterioneStacks.StackSpec(
                                            value.id(), value.material(), value.modelId(),
                                            value.displayName(), value.lore()))));
                });
            }
        }
        root.remove();
    }

    private void releaseCell(World world, int[] cell, Material replacement) {
        Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
        block.setType(replacement, false);
        block.getChunk().getPersistentDataContainer()
                .remove(cellKey(cell[0], cell[1], cell[2]));
    }

    // --- persistence / replay -----------------------------------------------------

    /**
     * Chunk-load replay: our roots re-register with the world as their chunk
     * loads — missing members are rebuilt (MTR-0026 info), visuals refresh
     * from the CURRENT definition of the STORED id (rebuild-visuals-only
     * policy), and due evolution stages apply from absolute timestamps.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ItemDisplay root)) {
            return;
        }
        String raw = root.getPersistentDataContainer().get(KEY_FURNITURE, PersistentDataType.STRING);
        if (raw == null) {
            return;
        }
        replayLater(root);
    }

    /** One tick later: the freshly loaded chunk is fully populated then. */
    private void replayLater(ItemDisplay root) {
        scheduling.executeSync(plugin, root.getLocation(), () -> {
            if (root.isValid()) {
                replay(root);
            }
        });
    }

    private void replay(ItemDisplay root) {
        NamespacedId id;
        try {
            id = NamespacedId.parse(definitionId(root));
        } catch (IllegalArgumentException e) {
            reportLimited("orphan-furniture", DiagnosticCodes.ORPHANED_PLACEMENT, Severity.INFO,
                    "furniture root at " + root.getLocation() + " records invalid id");
            return;
        }
        Optional<RuntimeFurniture> def = furnitures() == null
                ? Optional.empty() : furnitures().byId(id);
        if (def.isEmpty()) {
            reportLimited("orphan-furniture:" + id, DiagnosticCodes.ORPHANED_PLACEMENT, Severity.INFO,
                    "furniture cluster at " + root.getLocation() + " references unknown definition "
                            + id + " (kept; visuals frozen)");
            return;
        }
        // visuals-only refresh from the current definition
        root.setItemStack(displayStack(def.get()));

        // rebuild missing members against the stored record
        var record = root.getPersistentDataContainer();
        List<UUID> members = FurnitureMath.unpackUuids(
                record.get(KEY_MEMBERS, PersistentDataType.STRING));
        List<UUID> seats = FurnitureMath.unpackUuids(record.get(KEY_SEATS, PersistentDataType.STRING));
        long missing = members.stream().filter(memberId -> nearbyEntity(root, memberId) == null).count();
        if (missing > 0) {
            reportLimited("repair:" + id, DiagnosticCodes.ORPHANED_PLACEMENT, Severity.INFO,
                    "rebuilt " + missing + " missing furniture member(s) for " + id);
            // full respawn is the honest repair: kill and re-spawn the cluster in place
            respawnMembers(root, def.get());
            members = FurnitureMath.unpackUuids(record.get(KEY_MEMBERS, PersistentDataType.STRING));
            seats = FurnitureMath.unpackUuids(record.get(KEY_SEATS, PersistentDataType.STRING));
        }

        // lazy evolution: absolute timestamps (seconds since placement), never a scan timer
        Long placed = record.get(KEY_PLACED, PersistentDataType.LONG);
        Integer stage = record.get(KEY_STAGE, PersistentDataType.INTEGER);
        if (placed != null && stage != null) {
            evolve(root, def.get(), placed, stage);
        }
    }

    /**
     * Member repair respawns every derived entity fresh (interactions,
     * seats, label) and rewrites the member record — the root keeps its id,
     * timestamps and storage.
     */
    private void respawnMembers(ItemDisplay root, RuntimeFurniture def) {
        var record = root.getPersistentDataContainer();
        List<UUID> oldMembers = FurnitureMath.unpackUuids(
                record.get(KEY_MEMBERS, PersistentDataType.STRING));
        for (UUID memberId : oldMembers) {
            Entity member = nearbyEntity(root, memberId);
            if (member != null) {
                member.remove();
            }
        }
        Location center = root.getLocation();
        int x = center.getBlockX();
        int y = center.getBlockY();
        int z = center.getBlockZ();
        float yaw = root.getLocation().getYaw();

        var members = new ArrayList<UUID>();
        var seats = new ArrayList<UUID>();
        for (RuntimeFurniture.Hitbox hitbox : def.hitboxes()) {
            if (hitbox.isBarrier()) {
                continue; // cells persist as blocks, not entities
            }
            double[] rotated = FurnitureMath.rotate(hitbox.offset(), yaw);
            Interaction interaction = center.getWorld().spawn(new Location(center.getWorld(),
                    x + 0.5 + rotated[0], y + rotated[1], z + 0.5 + rotated[2]),
                    Interaction.class, entity -> {
                        entity.setInteractionWidth((float) hitbox.width());
                        entity.setInteractionHeight((float) hitbox.height());
                        entity.setResponsive(true);
                    });
            tagMember(interaction, root.getUniqueId());
            members.add(interaction.getUniqueId());
        }
        for (double[] seatOffset : def.seats()) {
            double[] pos = FurnitureMath.seatPosition(x, y, z, seatOffset, yaw);
            ArmorStand seat = center.getWorld().spawn(
                    new Location(center.getWorld(), pos[0], pos[1], pos[2]), ArmorStand.class,
                    stand -> {
                        stand.setVisible(false);
                        stand.setMarker(true);
                        stand.setSmall(true);
                        stand.setGravity(false);
                        stand.setInvulnerable(true);
                    });
            tagMember(seat, root.getUniqueId());
            seats.add(seat.getUniqueId());
            members.add(seat.getUniqueId());
        }
        if (def.label() != null) {
            TextDisplay label = center.getWorld().spawn(
                    new Location(center.getWorld(), x + 0.5, y + 1.25, z + 0.5), TextDisplay.class,
                    display -> {
                        display.text(LegacyComponentSerializer.legacyAmpersand().deserialize(def.label()));
                        display.setBillboard(Display.Billboard.CENTER);
                    });
            tagMember(label, root.getUniqueId());
            members.add(label.getUniqueId());
        }
        record.set(KEY_MEMBERS, PersistentDataType.STRING, FurnitureMath.packUuids(members));
        record.set(KEY_SEATS, PersistentDataType.STRING, FurnitureMath.packUuids(seats));
    }

    /**
     * Applies the furthest due evolution step ({@code after} = seconds since
     * placement), transforming the cluster in place — id, members, storage
     * and timestamps survive; only the visual changes.
     */
    private void evolve(ItemDisplay root, RuntimeFurniture def, long placedMillis, int appliedStages) {
        int targetIndex =
                FurnitureMath.dueStageIndex(def, placedMillis, System.currentTimeMillis(), appliedStages);
        if (targetIndex < 0) {
            return;
        }
        Optional<RuntimeFurniture> next = furnitures().byId(def.evolution().get(targetIndex).target());
        if (next.isEmpty()) {
            reportLimited("evolution:" + def.id(), DiagnosticCodes.ORPHANED_PLACEMENT, Severity.INFO,
                    "evolution target " + def.evolution().get(targetIndex).target()
                            + " is not defined; cluster kept");
            return;
        }
        root.setItemStack(displayStack(next.get()));
        root.getPersistentDataContainer().set(
                KEY_FURNITURE, PersistentDataType.STRING, next.get().id().toString());
        // the applied counter indexes the CURRENT definition's list — a
        // transform to another definition starts that list fresh (a self
        // target keeps counting so later replays skip the applied entry)
        root.getPersistentDataContainer().set(KEY_STAGE, PersistentDataType.INTEGER,
                next.get().id().equals(def.id()) ? targetIndex + 1 : 0);
    }

    // --- storage -----------------------------------------------------------------

    /** A persistent holder keyed to its cluster root. */
    public static final class FurnitureStorage implements InventoryHolder {
        private final UUID rootId;
        private Inventory inventory;

        FurnitureStorage(UUID rootId) {
            this.rootId = rootId;
        }

        void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        UUID rootId() {
            return rootId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private void openStorage(Player player, Entity root, RuntimeFurniture def) {
        ItemStack[] contents = readStorage(root.getPersistentDataContainer());
        FurnitureStorage holder = new FurnitureStorage(root.getUniqueId());
        holder.inventory(Bukkit.createInventory(holder, def.storage(),
                Component.text(def.displayName() == null ? "Furniture" : def.displayName())));
        holder.getInventory().setContents(contents);
        player.openInventory(holder.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof FurnitureStorage holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Entity root = nearbyEntityAround(player.getLocation(), holder.rootId());
        if (root == null) {
            return; // cluster died while open: contents spill through removal
        }
        writeStorage(root.getPersistentDataContainer(), event.getInventory().getContents());
    }

    /** Length-prefixed serialization of the storage grid through Paper's byte codec. */
    private static byte[] writeAll(List<ItemStack> stacks) {
        try {
            var out = new ByteArrayOutputStream();
            var data = new DataOutputStream(out);
            data.writeInt(stacks.size());
            for (ItemStack stack : stacks) {
                if (stack == null || stack.getType().isAir()) {
                    data.writeBoolean(false);
                    continue;
                }
                byte[] bytes = stack.serializeAsBytes();
                data.writeBoolean(true);
                data.writeInt(bytes.length);
                data.write(bytes);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return new byte[0]; // unreachable: memory streams
        }
    }

    private static ItemStack[] readAll(byte[] blob) {
        var out = new ArrayList<ItemStack>();
        if (blob == null || blob.length == 0) {
            return new ItemStack[0];
        }
        try {
            var data = new DataInputStream(new ByteArrayInputStream(blob));
            int count = data.readInt();
            for (int i = 0; i < count; i++) {
                boolean present = data.readBoolean();
                if (!present) {
                    out.add(null);
                    continue;
                }
                int length = data.readInt();
                byte[] bytes = new byte[length];
                if (data.read(bytes) != length) {
                    break;
                }
                out.add(ItemStack.deserializeBytes(bytes));
            }
        } catch (IOException | RuntimeException e) {
            // torn record beats a crash; contents degrade gracefully
        }
        return out.toArray(new ItemStack[0]);
    }

    private ItemStack[] readStorage(org.bukkit.persistence.PersistentDataContainer record) {
        return readAll(record.get(KEY_STORAGE, PersistentDataType.BYTE_ARRAY));
    }

    private void writeStorage(org.bukkit.persistence.PersistentDataContainer record, ItemStack[] contents) {
        List<ItemStack> list = new ArrayList<>(contents.length);
        for (ItemStack content : contents) {
            list.add(content);
        }
        record.set(KEY_STORAGE, PersistentDataType.BYTE_ARRAY, writeAll(list));
    }

    // --- dev surface ---------------------------------------------------------------

    /** Console-runnable placement (harness driver): yaw 0, no consumption. */
    public void devPlace(World world, int x, int y, int z, NamespacedId furnitureId, Consumer<String> status) {
        scheduling.executeSync(plugin, new Location(world, x, y, z), () -> {
            RuntimeFurnitures furnitures = furnitures();
            Optional<RuntimeFurniture> def = furnitures == null
                    ? Optional.empty() : furnitures.byId(furnitureId);
            if (def.isEmpty()) {
                status.accept("unknown-furniture " + furnitureId);
                return;
            }
            Block anchor = world.getBlockAt(x, y, z);
            if (!spaceOk(anchor, def.get(), 0f)) {
                status.accept("no-space " + furnitureId + " " + world.getName() + " " + x + " " + y + " " + z);
                return;
            }
            spawnCluster(world, x, y, z, def.get(), 0f);
            status.accept("placed " + furnitureId + " " + world.getName() + " " + x + " " + y + " " + z);
        });
    }

    /** Removes any furniture cluster anchored at the given cell; reports not-found otherwise. */
    public void devRemove(World world, int x, int y, int z, Consumer<String> status) {
        scheduling.executeSync(plugin, new Location(world, x, y, z), () -> {
            Block block = world.getBlockAt(x, y, z);
            Optional<UUID> rootId = rootUuidAt(block);
            if (rootId.isEmpty()) {
                status.accept("not-furniture " + world.getName() + " " + x + " " + y + " " + z);
                return;
            }
            Entity root = nearbyEntityAround(block.getLocation(), rootId.get());
            if (root == null) {
                status.accept("root-missing " + world.getName() + " " + x + " " + y + " " + z);
                return;
            }
            removeCluster(root, true);
            status.accept("removed furniture " + world.getName() + " " + x + " " + y + " " + z);
        });
    }

    /** Reports whether a furniture cluster is anchored at the given cell (dev driver). */
    public void devInspect(World world, int x, int y, int z, Consumer<String> status) {
        scheduling.executeSync(plugin, new Location(world, x, y, z), () -> {
            Block block = world.getBlockAt(x, y, z);
            Optional<UUID> rootId = rootUuidAt(block);
            if (rootId.isEmpty()) {
                status.accept("none " + world.getName() + " " + x + " " + y + " " + z);
                return;
            }
            Entity root = nearbyEntityAround(block.getLocation(), rootId.get());
            // display= pins the root's render position: the harness asserts the
            // model centers on the anchor cell (x.5, y.5, z.5)
            String display = root == null ? "?" : root.getLocation().getX() + "," + root.getLocation().getY()
                    + "," + root.getLocation().getZ();
            status.accept("furniture id=" + (root == null ? "?" : definitionId(root))
                    + " display=" + display
                    + " root=" + rootId.get()
                    + " " + world.getName() + " " + x + " " + y + " " + z);
        });
    }

    // --- helpers ----------------------------------------------------------------------

    private static final class NamedTextColorHolder {
        static final net.kyori.adventure.text.format.TextColor RED =
                net.kyori.adventure.text.format.NamedTextColor.RED;
    }

    private static net.kyori.adventure.text.format.TextColor NamedTextColor_RED() {
        return NamedTextColorHolder.RED;
    }

    private String displayName(RuntimeFurniture def) {
        return def.displayName() != null ? def.displayName() : def.id().value();
    }

    private String definitionId(Entity root) {
        return root.getPersistentDataContainer().get(KEY_FURNITURE, PersistentDataType.STRING);
    }

    private boolean isRoot(Entity entity) {
        return entity.getPersistentDataContainer().has(KEY_FURNITURE, PersistentDataType.STRING);
    }

    private Optional<UUID> memberRoot(Entity member) {
        String raw = member.getPersistentDataContainer().get(KEY_ROOT, PersistentDataType.STRING);
        try {
            return raw == null ? Optional.empty() : Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<RuntimeFurniture> definitionOf(Entity root) {
        RuntimeFurnitures furnitures = furnitures();
        if (furnitures == null) {
            return Optional.empty();
        }
        try {
            return furnitures.byId(NamespacedId.parse(definitionId(root)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Same-region uuid lookup (cluster spans a few blocks — never cross-region). */
    private Entity nearbyEntity(Entity anchor, UUID target) {
        if (anchor == null || target == null) {
            return null;
        }
        return nearbyEntityAround(anchor.getLocation(), target);
    }

    private Entity nearbyEntityAround(Location center, UUID target) {
        for (Entity candidate : center.getWorld().getNearbyEntities(center, 16, 16, 16)) {
            if (candidate.getUniqueId().equals(target)) {
                return candidate;
            }
        }
        return null;
    }

    private Entity nearestRoot(Location center, double radius) {
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity candidate : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (isRoot(candidate)) {
                double distance = candidate.getLocation().distanceSquared(center);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** The displayed stack: paper carrying the model component (no item identity). */
    private ItemStack displayStack(RuntimeFurniture def) {
        ItemStack stack = new ItemStack(Material.PAPER);
        if (def.modelRef() != null && def.modelRef().contains(":")) {
            stack.editMeta(meta -> meta.setItemModel(new org.bukkit.NamespacedKey(
                    def.modelRef().substring(0, def.modelRef().indexOf(':')),
                    def.modelRef().substring(def.modelRef().indexOf(':') + 1))));
        }
        return stack;
    }

    private void reportLimited(String subject, DiagnosticCode code, Severity severity, String message) {
        long now = System.currentTimeMillis();
        Long last = lastReport.get(subject);
        if (last != null && now - last < 1000) {
            return;
        }
        if (lastReport.size() > 4096) {
            lastReport.clear();
        }
        lastReport.put(subject, now);
        diagnostics.publish(new Diagnostic(code, severity, message, null, null, null));
    }
}
