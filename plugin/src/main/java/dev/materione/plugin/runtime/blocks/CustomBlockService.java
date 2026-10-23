package dev.materione.plugin.runtime.blocks;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.blocks.BlockStateSpaces;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.runtime.RuntimeBlock;
import dev.materione.core.runtime.RuntimeBlocks;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.core.storage.PlacementIndex;
import dev.materione.platform.items.MaterioneStacks;
import dev.materione.plugin.runtime.behavior.BehaviorService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Note;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.NoteBlock;
import org.bukkit.block.data.type.Tripwire;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

/**
 * The custom-block world runtime (runtime-systems §4, M2 slice 3). Reads are
 * state-as-identity: decode the host blockstate into a slot int and answer
 * with one precomputed array lookup — vanilla positions cost no PDC access.
 * The chunk PDC stays the truth (§4.2): a decoded candidate is confirmed
 * against the recorded id before anything acts on it, and a mismatch is
 * quarantined (MTR-0024) instead of guessed at. Writes ride the real Bukkit
 * events — placement after protection plugins had their say (MONITOR), a
 * single shared removal pipeline for every cause (player break, explosion,
 * piston {@code move_policy: break}, external conversions) — and batch their
 * chunk-PDC plus index updates per event.
 *
 * <p>Folia discipline: handlers run where the event fired; cross-position
 * work (pistons, sweeps) hops through the scheduling gateway per region.
 */
public final class CustomBlockService implements Listener {

    private static final Set<Material> STATE_HOSTS = Set.of(
            Material.NOTE_BLOCK, Material.TRIPWIRE, Material.CHORUS_PLANT);

    /**
     * PDC-identified hosts with a fixed vanilla block: shaped stairs/slabs
     * (M2 D7). Their state carries facing/half/type functionally, so
     * identity comes only from the chunk record.
     */
    private static final Set<Material> SHAPED_HOSTS = Set.of(
            Material.WAXED_CUT_COPPER_STAIRS, Material.WAXED_CUT_COPPER_SLAB);

    private final org.bukkit.plugin.Plugin plugin;
    private final DiagnosticsBus diagnostics;
    private final SchedulingGateway scheduling;
    private final dev.materione.core.runtime.ContentRuntime runtime;
    private final BehaviorService behavior;
    private final PlacementIndex index;

    /** Per-key rate limiting for world diagnostics (MTR-0024/0026). */
    private final Map<String, Long> lastReport = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot;

    private static final class Snapshot {
        final RuntimeRegistries source;
        final RuntimeBlocks blocks;
        final Set<String> technicalHosts;

        Snapshot(RuntimeRegistries source, RuntimeBlocks blocks, Set<String> technicalHosts) {
            this.source = source;
            this.blocks = blocks;
            this.technicalHosts = technicalHosts;
        }
    }

    public CustomBlockService(
            org.bukkit.plugin.Plugin plugin,
            DiagnosticsBus diagnostics,
            SchedulingGateway scheduling,
            dev.materione.core.runtime.ContentRuntime runtime,
            BehaviorService behavior,
            PlacementIndex index) {
        this.plugin = plugin;
        this.diagnostics = diagnostics;
        this.scheduling = scheduling;
        this.runtime = runtime;
        this.behavior = behavior;
        this.index = index;
    }

    // --- identification -------------------------------------------------------

    /** Per-snapshot derived lookup; rebuilt lazily when a swap publishes new registries. */
    private Snapshot snapshot() {
        RuntimeRegistries current = runtime.registries();
        Snapshot cached = snapshot;
        if (cached == null || cached.source != current) {
            RuntimeBlocks blocks = current.view(RuntimeBlocks.KEY);
            var technical = new HashSet<String>();
            if (blocks != null) {
                for (RuntimeBlock block : blocks.all()) {
                    if (!block.allocatesState()) {
                        technical.add(block.host());
                    }
                }
            }
            cached = new Snapshot(current, blocks, technical);
            snapshot = cached;
        }
        return cached;
    }

    /**
     * Identifies the custom block at {@code block}, or empty for everything
     * vanilla. Fast path: decode slot → array → only mapped slots touch the
     * chunk PDC; a candidate without a matching record is reported (MTR-0024)
     * and treated as not ours.
     */
    public Optional<RuntimeBlock> identify(Block block) {
        Material type = block.getType();
        boolean stateHost = STATE_HOSTS.contains(type);
        boolean pdcOnly = SHAPED_HOSTS.contains(type)
                || snapshot().technicalHosts.contains(type.name().toLowerCase(Locale.ROOT));
        if (!stateHost && !pdcOnly) {
            return Optional.empty();
        }
        Optional<RuntimeBlock> candidate =
                stateHost ? snapshot().blocks.bySlot(hostKey(type), decodedSlot(block)) : Optional.empty();
        Optional<NamespacedId> recorded =
                ChunkBlocks.idAt(block.getChunk(), block.getX(), block.getY(), block.getZ());
        if (candidate.isPresent()) {
            RuntimeBlock def = candidate.get();
            if (recorded.isPresent() && recorded.get().equals(def.id())) {
                return Optional.of(def);
            }
            reportLimited("mismatch:" + posKey(block), DiagnosticCodes.BLOCK_DATA_MISMATCH, Severity.WARN,
                    "block at " + describe(block) + " decodes to " + def.id()
                            + " but records " + recorded.map(NamespacedId::toString).orElse("nothing"));
            return Optional.empty();
        }
        // no state candidate: technical hosts and unknown-record cases
        if (recorded.isEmpty()) {
            return Optional.empty();
        }
        Optional<RuntimeBlock> known = snapshot().blocks.byId(recorded.get());
        if (known.isEmpty()) {
            reportLimited("orphan:" + recorded.get(), DiagnosticCodes.ORPHANED_PLACEMENT, Severity.INFO,
                    "placement at " + describe(block) + " references unknown definition "
                            + recorded.get() + " (kept in place; /materione dev sweep)");
            return Optional.empty();
        }
        if (known.get().allocatesState()) {
            reportLimited("mismatch:" + posKey(block), DiagnosticCodes.BLOCK_DATA_MISMATCH, Severity.WARN,
                    "block at " + describe(block) + " records " + recorded.get()
                            + " but its state decodes elsewhere");
            return Optional.empty();
        }
        return known;
    }

    /** The identity bit pattern encoded in a state-host position (-1 when undecodable). */
    private static int decodedSlot(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof NoteBlock note) {
            return BlockStateSpaces.noteSlot(
                    note.getInstrument().ordinal(), note.getNote().getId(), note.isPowered());
        }
        if (data instanceof Tripwire wire) {
            int bits = (wire.hasFace(BlockFace.EAST) ? 1 : 0) | (wire.hasFace(BlockFace.WEST) ? 2 : 0)
                    | (wire.hasFace(BlockFace.SOUTH) ? 4 : 0) | (wire.hasFace(BlockFace.NORTH) ? 8 : 0)
                    | (wire.isAttached() ? 16 : 0) | (wire.isDisarmed() ? 32 : 0)
                    | (wire.isPowered() ? 64 : 0);
            return bits == 0 ? -1 : bits; // slot 0 is reserved wire
        }
        if (data instanceof MultipleFacing facing) {
            return (facing.hasFace(BlockFace.UP) ? 1 : 0) | (facing.hasFace(BlockFace.DOWN) ? 2 : 0)
                    | (facing.hasFace(BlockFace.NORTH) ? 4 : 0) | (facing.hasFace(BlockFace.EAST) ? 8 : 0)
                    | (facing.hasFace(BlockFace.SOUTH) ? 16 : 0) | (facing.hasFace(BlockFace.WEST) ? 32 : 0);
        }
        return -1;
    }

    // --- placement ------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack held = event.getItemInHand();
        Optional<NamespacedId> itemId = MaterioneStacks.identityOf(held);
        if (itemId.isEmpty()) {
            return;
        }
        RuntimeBlocks blocks = snapshot().blocks;
        if (blocks == null) {
            return;
        }
        Optional<NamespacedId> blockId = blocks.placedBy(itemId.get());
        if (blockId.isEmpty()) {
            return;
        }
        Optional<RuntimeBlock> def = blocks.byId(blockId.get());
        if (def.isEmpty()) {
            diagnostics.publish(Diagnostic.warn(DiagnosticCodes.ORPHANED_PLACEMENT,
                    "item " + itemId.get() + " places unknown block " + blockId.get()));
            return;
        }
        Block placed = event.getBlockPlaced();
        if (stackSlab(placed, event.getBlockAgainst(), def.get())) {
            // same-cell stacking: the PDC record already names this id
        } else {
            place(placed, def.get(), orientationOf(event.getPlayer(), event.getBlockAgainst(), placed));
        }
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.PLACE, null, event.getPlayer(),
                placed.getLocation(), def.get().id(),
                def.get().technique().name().toLowerCase(Locale.ROOT), null);
    }

    /**
     * Vanilla slab stacking: the client targets the occupied cell when the
     * click fills a slab's empty half. Replacing would drop the existing
     * half, so the state flips to a double instead — identity (chunk PDC,
     * index) is unchanged, only the rendered state. The compiler emits the
     * {@code type=double} model, so the cell renders as the full block.
     */
    private static boolean stackSlab(Block placed, Block against, RuntimeBlock def) {
        if (def.technique() != BlockStateSpaces.Technique.SLAB || !placed.equals(against)) {
            return false;
        }
        if (!(placed.getBlockData() instanceof org.bukkit.block.data.type.Slab existing)
                || existing.getType() == org.bukkit.block.data.type.Slab.Type.DOUBLE) {
            return false;
        }
        var data = (org.bukkit.block.data.type.Slab) Material.WAXED_CUT_COPPER_SLAB.createBlockData();
        data.setType(org.bukkit.block.data.type.Slab.Type.DOUBLE);
        placed.setBlockData(data, false);
        return true;
    }

    /**
     * The functional orientation for shaped techniques: stairs face the way
     * the placer looks (vanilla — the tall side away from them), half/type
     * follow the clicked face (up face → bottom, down face → top) and, for
     * side faces, the exact hit point (vanilla convention). State-space
     * techniques ignore it.
     */
    private Orientation orientationOf(Player player, Block against, Block placed) {
        BlockFace face = faceBetween(against, placed);
        boolean top;
        if (face == BlockFace.DOWN) {
            top = true;
        } else if (face == BlockFace.UP) {
            top = false;
        } else {
            top = hitTopHalf(player);
        }
        return new Orientation(player.getFacing(), top);
    }

    /** The face of {@code against} the placement happened on; null for same-cell. */
    private static BlockFace faceBetween(Block against, Block placed) {
        int dx = placed.getX() - against.getX();
        int dy = placed.getY() - against.getY();
        int dz = placed.getZ() - against.getZ();
        if (dx == 1) {
            return BlockFace.EAST;
        }
        if (dx == -1) {
            return BlockFace.WEST;
        }
        if (dy == 1) {
            return BlockFace.UP;
        }
        if (dy == -1) {
            return BlockFace.DOWN;
        }
        if (dz == 1) {
            return BlockFace.SOUTH;
        }
        if (dz == -1) {
            return BlockFace.NORTH;
        }
        return null;
    }

    /** Whether the crosshair hits the upper half of the targeted block cell. */
    private boolean hitTopHalf(Player player) {
        try {
            org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER);
            if (hit != null && hit.getHitPosition() != null) {
                double y = hit.getHitPosition().getY();
                return y - Math.floor(y) >= 0.5;
            }
        } catch (RuntimeException ignored) {
            // fall through to the default half
        }
        return false;
    }

    private record Orientation(BlockFace facing, boolean top) {
        static final Orientation DEFAULT = new Orientation(BlockFace.NORTH, false);
    }

    /** Swaps the host data in and records the placement (PDC + index). */
    public void place(Block block, RuntimeBlock def) {
        place(block, def, Orientation.DEFAULT);
    }

    /** Swaps the host data in and records the placement (PDC + index). */
    public void place(Block block, RuntimeBlock def, Orientation orientation) {
        try {
            block.setBlockData(encode(def, orientation), false);
        } catch (RuntimeException e) {
            reportLimited("encode:" + def.id(), DiagnosticCodes.PIPELINE_STAGE_FAILED, Severity.WARN,
                    "could not encode " + def.id() + ": " + e.getMessage());
            return;
        }
        ChunkBlocks.setIdAt(block.getChunk(), block.getX(), block.getY(), block.getZ(), def.id());
        index.add(positionOf(block), def.id().toString());
    }

    /** The host BlockData encoding a definition's allocated slot (or shaped orientation). */
    public BlockData encode(RuntimeBlock def) {
        return encode(def, Orientation.DEFAULT);
    }

    /** The host BlockData encoding a definition's allocated slot (or shaped orientation). */
    public BlockData encode(RuntimeBlock def, Orientation orientation) {
        return switch (def.technique()) {
            case NOTE_BLOCK -> {
                NoteBlock data = (NoteBlock) Material.NOTE_BLOCK.createBlockData();
                int[] parts = BlockStateSpaces.noteParts(def.slot());
                data.setInstrument(org.bukkit.Instrument.values()[parts[0]]);
                data.setNote(new Note(parts[1]));
                data.setPowered(parts[2] == 1);
                yield data;
            }
            case STRING -> {
                Tripwire data = (Tripwire) Material.TRIPWIRE.createBlockData();
                int slot = def.slot();
                data.setFace(BlockFace.EAST, (slot & 1) != 0);
                data.setFace(BlockFace.WEST, (slot & 2) != 0);
                data.setFace(BlockFace.SOUTH, (slot & 4) != 0);
                data.setFace(BlockFace.NORTH, (slot & 8) != 0);
                data.setAttached((slot & 16) != 0);
                data.setDisarmed((slot & 32) != 0);
                data.setPowered((slot & 64) != 0);
                yield data;
            }
            case CHORUS -> {
                MultipleFacing data = (MultipleFacing) Material.CHORUS_PLANT.createBlockData();
                int slot = def.slot();
                data.setFace(BlockFace.UP, (slot & 1) != 0);
                data.setFace(BlockFace.DOWN, (slot & 2) != 0);
                data.setFace(BlockFace.NORTH, (slot & 4) != 0);
                data.setFace(BlockFace.EAST, (slot & 8) != 0);
                data.setFace(BlockFace.SOUTH, (slot & 16) != 0);
                data.setFace(BlockFace.WEST, (slot & 32) != 0);
                yield data;
            }
            case TECHNICAL -> org.bukkit.Bukkit.createBlockData(def.host());
            case STAIRS -> {
                org.bukkit.block.data.type.Stairs data =
                        (org.bukkit.block.data.type.Stairs) Material.WAXED_CUT_COPPER_STAIRS
                                .createBlockData();
                data.setFacing(orientation.facing());
                data.setHalf(orientation.top()
                        ? org.bukkit.block.data.Bisected.Half.TOP : org.bukkit.block.data.Bisected.Half.BOTTOM);
                data.setShape(org.bukkit.block.data.type.Stairs.Shape.STRAIGHT);
                yield data;
            }
            case SLAB -> {
                org.bukkit.block.data.type.Slab data =
                        (org.bukkit.block.data.type.Slab) Material.WAXED_CUT_COPPER_SLAB.createBlockData();
                data.setType(orientation.top()
                        ? org.bukkit.block.data.type.Slab.Type.TOP : org.bukkit.block.data.type.Slab.Type.BOTTOM);
                yield data;
            }
        };
    }

    // --- removal --------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Optional<RuntimeBlock> found = identify(block);
        if (found.isEmpty()) {
            return;
        }
        RuntimeBlock def = found.get();
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.BREAK, null, event.getPlayer(),
                block.getLocation(), def.id(),
                def.technique().name().toLowerCase(Locale.ROOT), event);
        if (event.isCancelled()) {
            return; // a rule kept the block alive
        }
        event.setCancelled(true); // we own the teardown
        GameMode mode = event.getPlayer().getGameMode();
        remove(block, def, mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR);
    }

    /**
     * The single removal pipeline (every cause funnels here): PDC clear,
     * index tombstone, drops, then air. Callers fire ECA events themselves
     * when a player caused the break.
     */
    public void remove(Block block, RuntimeBlock def, boolean drop) {
        ChunkBlocks.setIdAt(block.getChunk(), block.getX(), block.getY(), block.getZ(), null);
        index.remove(positionOf(block));
        if (drop) {
            dropDrops(def, block.getLocation());
        }
        block.setType(Material.AIR, false);
    }

    private void dropDrops(RuntimeBlock def, Location location) {
        for (NamespacedId drop : def.drops()) {
            try {
                if ("minecraft".equals(drop.namespace())) {
                    Material material = Material.matchMaterial(drop.value());
                    if (material != null && material.isItem()) {
                        location.getWorld().dropItemNaturally(location, new ItemStack(material));
                    }
                } else {
                    Optional<RuntimeItem> item = runtime.registries().item(drop);
                    if (item.isPresent()) {
                        RuntimeItem spec = item.get();
                        location.getWorld().dropItemNaturally(location, MaterioneStacks.build(
                                new MaterioneStacks.StackSpec(spec.id(), spec.material(), spec.modelId(),
                                        spec.displayName(), spec.lore())));
                    }
                }
            } catch (RuntimeException e) {
                reportLimited("drop:" + drop, DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                        "dropping " + drop + " failed: " + e.getMessage());
            }
        }
    }

    // --- interact -------------------------------------------------------------

    /**
     * Placement for non-placeable placement items against vanilla blocks
     * (runtime-systems §13): a linked item whose material is not a block (a
     * paper talisman, a ruby) never produces a BlockPlaceEvent, so
     * right-clicking places the block against the clicked face directly.
     * Placeable materials keep the vanilla path — the MONITOR
     * BlockPlaceEvent handler owns them. Clicks on our own blocks are owned
     * by the interact handler below (CraftBukkit's short-circuit makes
     * vanilla placement unreachable there), hence the custom-block guard.
     * Furniture cells are not placement surfaces (their click semantics
     * stay intact).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlaceInteract(PlayerInteractEvent event) {
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return; // item use was forbidden — placement rides item use
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || event.getBlockFace() == null) {
            return;
        }
        ItemStack held = event.getItem();
        if (held == null || held.getType().isAir() || held.getType().isBlock()) {
            return; // placeable materials ride BlockPlaceEvent
        }
        Optional<NamespacedId> itemId = MaterioneStacks.identityOf(held);
        if (itemId.isEmpty()) {
            return;
        }
        RuntimeBlocks blocks = snapshot().blocks;
        if (blocks == null) {
            return;
        }
        Optional<RuntimeBlock> def = blocks.placedBy(itemId.get()).flatMap(blocks::byId);
        if (def.isEmpty()) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (identify(clicked).isPresent()
                || dev.materione.plugin.runtime.furniture.FurnitureService.isFurnitureCell(clicked)) {
            return; // our blocks route through onInteract; furniture is not a surface
        }
        Block target = clicked.getRelative(event.getBlockFace());
        if (!target.isReplaceable() || identify(target).isPresent()) {
            return; // no room — vanilla use of the clicked block proceeds
        }
        event.setCancelled(true);
        place(target, def.get(), orientationOf(event.getPlayer(), clicked, target));
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.PLACE, null, event.getPlayer(),
                target.getLocation(), def.get().id(),
                def.get().technique().name().toLowerCase(Locale.ROOT), null);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
    }

    /**
     * Clicks on our blocks are ours alone — the host's vanilla use behavior
     * (note tuning and play, any interactive technical host) never runs.
     * CraftBukkit short-circuits the whole interaction once the block
     * result is DENY, so vanilla placement is unreachable against our
     * blocks and the runtime does it itself: a right-click carrying one of
     * our placement items places that block against the clicked face
     * directly (sneaking or not — the same rule as any normal block), a
     * vanilla block is placed against us with best-effort orientation
     * ({@link #placeVanillaItem}), and sneaking with anything else lets
     * vanilla place it (it skips the host's use itself, and onPlace
     * converts ours). Every other right-click is the block's own:
     * cancelled for vanilla, with ECA interact rules firing once, on the
     * main hand. Quarantined positions (record present, state drifted) are
     * denied too but fire nothing — their state is not trusted. Left clicks
     * stay vanilla; the break pipeline owns teardown (§4.2).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        boolean right = event.getAction() == Action.RIGHT_CLICK_BLOCK;
        boolean left = event.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        Optional<RuntimeBlock> found = identify(block);
        if (found.isEmpty() && !(right && recorded(block))) {
            return; // a vanilla block: vanilla use proceeds
        }
        boolean mainHand = event.getHand() == EquipmentSlot.HAND;
        if (right && mainHand && placePlacementItem(event, block, found)) {
            return; // a placement item put its block against us
        }
        if (right && sneakingWithItem(event)) {
            return; // vanilla skips the host's use itself and places against us
        }
        if (right && mainHand && placeVanillaItem(event, block)) {
            return; // a vanilla block was placed against us (best-effort orientation)
        }
        if (right) {
            event.setCancelled(true); // the host's use — and vanilla item use — never runs
        }
        if (found.isEmpty() || !mainHand) {
            return;
        }
        RuntimeBlock def = found.get();
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.INTERACT,
                right ? "right" : "left", event.getPlayer(), block.getLocation(), def.id(),
                def.technique().name().toLowerCase(Locale.ROOT), event);
    }

    /**
     * Places the held placement item's block against {@code clicked}'s face
     * and consumes the click. Returns false when the held item is not a
     * placement item or there is no room — the caller then treats the click
     * as an interact. Vanilla stacking is mirrored for slabs: a slab item
     * clicked into one of our slabs' empty halves doubles that cell instead
     * of placing beside it (the BlockPlaceEvent path does the same).
     */
    private boolean placePlacementItem(PlayerInteractEvent event, Block clicked,
            Optional<RuntimeBlock> clickedDef) {
        ItemStack held = event.getItem();
        if (held == null || held.getType().isAir() || event.getBlockFace() == null) {
            return false;
        }
        Optional<NamespacedId> itemId = MaterioneStacks.identityOf(held);
        if (itemId.isEmpty()) {
            return false;
        }
        RuntimeBlocks blocks = snapshot().blocks;
        if (blocks == null) {
            return false;
        }
        Optional<RuntimeBlock> def = blocks.placedBy(itemId.get()).flatMap(blocks::byId);
        if (def.isEmpty()) {
            return false;
        }
        boolean stacked = def.get().technique() == BlockStateSpaces.Technique.SLAB
                && clickedDef.isPresent() && clickedDef.get().id().equals(def.get().id())
                && slabHalfClicked(event.getBlockFace(), clicked)
                && stackSlab(clicked, clicked, def.get());
        Block target = clicked.getRelative(event.getBlockFace());
        if (!stacked && (!target.isReplaceable() || identify(target).isPresent())) {
            return false; // no room — the click falls through to the interact below
        }
        event.setCancelled(true); // both results: we own this click end to end
        if (!stacked) {
            place(target, def.get(), orientationOf(event.getPlayer(), clicked, target));
        }
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.PLACE, null, event.getPlayer(),
                (stacked ? clicked : target).getLocation(), def.get().id(),
                def.get().technique().name().toLowerCase(Locale.ROOT), null);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        return true;
    }

    /** Whether {@code face} points into a slab cell's empty half. */
    private static boolean slabHalfClicked(BlockFace face, Block slab) {
        if (!(slab.getBlockData() instanceof org.bukkit.block.data.type.Slab data)
                || data.getType() == org.bukkit.block.data.type.Slab.Type.DOUBLE) {
            return false;
        }
        return (face == BlockFace.UP && data.getType() == org.bukkit.block.data.type.Slab.Type.BOTTOM)
                || (face == BlockFace.DOWN && data.getType() == org.bukkit.block.data.type.Slab.Type.TOP);
    }

    /**
     * Places a held vanilla block against {@code clicked}'s face. Vanilla
     * placement code never runs against our blocks (the interact
     * short-circuit), so this mirrors the parts of {@code BlockItem#useOn}
     * that matter for building — slab/stairs/trapdoor halves from the hit
     * point, log axes from the clicked face, directional fronts toward the
     * player, waterlogged placement into water, support checks, no placement
     * inside entities — and announces the result through a real
     * BlockPlaceEvent so protection plugins keep their say. Multi-cell
     * structures (doors, beds) return false: sneaking still places those
     * through vanilla. Returns false when nothing was placed — the click
     * then falls through to the interact below.
     */
    private boolean placeVanillaItem(PlayerInteractEvent event, Block clicked) {
        ItemStack held = event.getItem();
        if (held == null || held.getType().isAir() || !held.getType().isBlock()
                || event.getBlockFace() == null) {
            return false;
        }
        BlockFace face = event.getBlockFace();
        Block target = clicked.getRelative(face);
        if (!target.isReplaceable() || identify(target).isPresent()) {
            return false; // no room — the click falls through to the interact below
        }
        BlockData data = held.getType().createBlockData();
        if (data instanceof org.bukkit.block.data.type.Door || data instanceof org.bukkit.block.data.type.Bed) {
            return false; // multi-cell structures: vanilla places those (sneak)
        }
        Player player = event.getPlayer();
        boolean hitTop = face != BlockFace.UP && face != BlockFace.DOWN && hitTopHalf(player);
        if (data instanceof org.bukkit.block.data.Waterlogged waterlogged && target.isLiquid()) {
            waterlogged.setWaterlogged(true);
        }
        if (data instanceof org.bukkit.block.data.Bisected bisected) {
            bisected.setHalf(face == BlockFace.DOWN || hitTop
                    ? org.bukkit.block.data.Bisected.Half.TOP : org.bukkit.block.data.Bisected.Half.BOTTOM);
        }
        if (data instanceof org.bukkit.block.data.type.Slab slab) {
            slab.setType(face == BlockFace.DOWN || hitTop
                    ? org.bukkit.block.data.type.Slab.Type.TOP : org.bukkit.block.data.type.Slab.Type.BOTTOM);
        }
        if (data instanceof org.bukkit.block.data.Orientable orientable) { // logs, pillars
            orientable.setAxis(switch (face) {
                case UP, DOWN -> org.bukkit.Axis.Y;
                case NORTH, SOUTH -> org.bukkit.Axis.Z;
                default -> org.bukkit.Axis.X;
            });
        }
        if (data instanceof org.bukkit.block.data.type.Stairs stairs) {
            stairs.setFacing(player.getFacing()); // the full side goes away from the placer
        } else if (data instanceof org.bukkit.block.data.Directional directional) { // furnaces, pistons…
            BlockFace want = face.getOppositeFace();
            if (want == BlockFace.UP || want == BlockFace.DOWN
                    || !directional.getFaces().contains(want)) {
                want = player.getFacing().getOppositeFace(); // fronts face the placer
            }
            if (directional.getFaces().contains(want)) {
                directional.setFacing(want);
            }
        }
        if (data instanceof org.bukkit.block.data.Rotatable rotatable) { // floor signs
            rotatable.setRotation(player.getFacing().getOppositeFace()); // text faces the placer
        }
        if (!data.isSupported(target)) {
            return false; // torches in the air, rails without ground, …
        }
        if (held.getType().isOccluding() && !entitiesClear(target)) {
            return false; // vanilla refuses full cubes placed inside entities
        }
        org.bukkit.block.BlockState replaced = target.getState();
        target.setBlockData(data);
        BlockPlaceEvent announced = new BlockPlaceEvent(
                target, replaced, clicked, held, player, true, event.getHand());
        if (!announced.callEvent()) {
            target.setBlockData(replaced.getBlockData());
            return false; // a plugin vetoed it — the click falls through
        }
        if (player.getGameMode() != GameMode.CREATIVE) {
            held.setAmount(held.getAmount() - 1);
        }
        return true;
    }

    /** Whether no living entity occupies {@code target}'s cell. */
    private static boolean entitiesClear(Block target) {
        return target.getWorld().getNearbyEntities(
                org.bukkit.util.BoundingBox.of(target.getLocation().add(0.5, 0.5, 0.5), 0.99, 0.99, 0.99),
                entity -> entity instanceof org.bukkit.entity.LivingEntity).isEmpty();
    }

    /**
     * Vanilla skips a block's own use when the player sneaks with an item
     * in either hand — the one path where vanilla itself places against our
     * blocks without ever using the host.
     */
    private static boolean sneakingWithItem(PlayerInteractEvent event) {
        if (!event.getPlayer().isSneaking()) {
            return false;
        }
        org.bukkit.inventory.PlayerInventory inventory = event.getPlayer().getInventory();
        return !inventory.getItem(EquipmentSlot.HAND).getType().isAir()
                || !inventory.getItem(EquipmentSlot.OFF_HAND).getType().isAir();
    }

    /** Whether the chunk PDC records a Materione placement at {@code block}. */
    private static boolean recorded(Block block) {
        return ChunkBlocks.idAt(block.getChunk(), block.getX(), block.getY(), block.getZ()).isPresent();
    }

    // --- physics suppression fallbacks ----------------------------------------

    /**
     * Cancels recalculation of placed identities (connection bits ARE the
     * identity on string/chorus). Only confirmed placements cancel — vanilla
     * redstone around them runs untouched.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (!isHostMaterial(block.getType())) {
            return;
        }
        if (identify(block).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onNotePlay(NotePlayEvent event) {
        if (identify(event.getBlock()).isPresent()) {
            event.setCancelled(true);
        }
    }

    /** Keeps walk-through entities from toggling tripwire/string states. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInside(io.papermc.paper.event.entity.EntityInsideBlockEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.TRIPWIRE && block.getType() != Material.CHORUS_PLANT) {
            return;
        }
        if (identify(block).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (!isHostMaterial(to.getType())) {
            return;
        }
        if (identify(to).isPresent()) {
            event.setCancelled(true);
        }
    }

    // --- pistons / explosions / conversions ------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        handlePiston(event.getBlocks(), event.getDirection(), 1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        handlePiston(event.getBlocks(), event.getDirection(), -1);
    }

    /** keep-policy blocks migrate their record with the move; break-policy blocks drop first. */
    private void handlePiston(List<Block> moved, BlockFace direction, int sign) {
        List<Block[]> migrations = new ArrayList<>();
        List<RuntimeBlock> migratedDefs = new ArrayList<>();
        for (Block block : moved) {
            Optional<RuntimeBlock> found = identify(block);
            if (found.isEmpty()) {
                continue;
            }
            RuntimeBlock def = found.get();
            if ("break".equals(def.movePolicy())) {
                remove(block, def, true);
                continue;
            }
            migrations.add(new Block[] {block, block.getRelative(direction, sign)});
            migratedDefs.add(def);
        }
        for (int i = 0; i < migrations.size(); i++) {
            Block from = migrations.get(i)[0];
            Block to = migrations.get(i)[1];
            ChunkBlocks.setIdAt(from.getChunk(), from.getX(), from.getY(), from.getZ(), null);
            index.remove(positionOf(from));
            ChunkBlocks.setIdAt(to.getChunk(), to.getX(), to.getY(), to.getZ(), migratedDefs.get(i).id());
            index.add(positionOf(to), migratedDefs.get(i).id().toString());
        }
    }

    /** blast_resistant placements survive; others leave through the pipeline before vanilla acts. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        filterExplosion(event.blockList());
    }

    private void filterExplosion(List<Block> blocks) {
        Iterator<Block> it = blocks.iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (!isHostMaterial(block.getType())) {
                continue;
            }
            Optional<RuntimeBlock> found = identify(block);
            if (found.isEmpty()) {
                continue;
            }
            it.remove();
            if (!found.get().blastResistant()) {
                remove(block, found.get(), false);
            }
        }
    }

    /**
     * External conversions of our hosts (chorus pop-off when its support
     * breaks, falling-entity landings): run the removal pipeline first so
     * drops, PDC and index stay consistent with what the world shows.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (!isHostMaterial(block.getType())) {
            return;
        }
        Optional<RuntimeBlock> found = identify(block);
        if (found.isPresent()) {
            remove(block, found.get(), true);
        }
    }

    // --- pick-block -------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickItem(io.papermc.paper.event.player.PlayerPickItemEvent event) {
        Player player = event.getPlayer();
        RayTraceResult hit;
        try {
            hit = player.rayTraceBlocks(8.0, FluidCollisionMode.NEVER);
        } catch (RuntimeException e) {
            return;
        }
        if (hit == null || hit.getHitBlock() == null) {
            return;
        }
        Optional<RuntimeBlock> found = identify(hit.getHitBlock());
        if (found.isEmpty()) {
            return;
        }
        Optional<NamespacedId> itemId = snapshot().blocks.itemFor(found.get().id());
        Optional<RuntimeItem> item = itemId.flatMap(id -> runtime.registries().item(id));
        if (item.isEmpty()) {
            return;
        }
        RuntimeItem spec = item.get();
        ItemStack stack = MaterioneStacks.build(new MaterioneStacks.StackSpec(
                spec.id(), spec.material(), spec.modelId(), spec.displayName(), spec.lore()));
        int slot = event.getTargetSlot();
        scheduling.executeEntity(plugin, player, () -> player.getInventory().setItem(slot, stack));
    }

    // --- index rebuild (§4.2: the accelerator is disposable) ---------------------

    /** This plugin's per-position chunk-PDC keys: {@code x{0-15}y{abs}z{0-15}}. */
    private static final java.util.regex.Pattern LOCAL_KEY =
            java.util.regex.Pattern.compile("x(\\d{1,2})y(-?\\d+)z(\\d{1,2})");

    /**
     * Rebuilds missing index entries when a chunk carrying our records loads
     * (copied world folder, restored backup, wiped storage): the chunk PDC is
     * the truth; the placement index only accelerates sweeps.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        reconcileChunk(event.getChunk());
    }

    /**
     * One-pass reconciliation for chunks that loaded before listeners existed
     * (worlds load before plugins enable, so spawn-area placements never fire
     * {@link #onChunkLoad}). Cheap when no custom blocks exist: the scan is
     * one empty-PDC key set per loaded chunk.
     */
    public void reconcileLoadedChunks() {
        for (World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                reconcileChunk(chunk);
            }
        }
    }

    private void reconcileChunk(org.bukkit.Chunk chunk) {
        Set<org.bukkit.NamespacedKey> keys;
        try {
            keys = chunk.getPersistentDataContainer().getKeys();
        } catch (RuntimeException e) {
            return; // chunk unloaded mid-scan: its next load retries
        }
        String worldName = chunk.getWorld().getName();
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        for (org.bukkit.NamespacedKey key : keys) {
            if (!ChunkBlocks.NAMESPACE.equals(key.getNamespace())) {
                continue;
            }
            var matcher = LOCAL_KEY.matcher(key.getKey());
            if (!matcher.matches()) {
                continue;
            }
            int x = baseX + Integer.parseInt(matcher.group(1));
            int y = Integer.parseInt(matcher.group(2));
            int z = baseZ + Integer.parseInt(matcher.group(3));
            Optional<NamespacedId> recorded = ChunkBlocks.idAt(chunk, x, y, z);
            if (recorded.isEmpty()) {
                continue;
            }
            var position = new PlacementIndex.Position(worldName, x, y, z);
            if (!recorded.get().toString().equals(index.at(position))) {
                index.add(position, recorded.get().toString());
            }
        }
    }

    // --- dev surface (/materione dev …) ------------------------------------------

    /** Places by id at explicit coordinates; runs on the target's region thread. */
    public void devPlace(World world, int x, int y, int z, NamespacedId blockId, Consumer<String> status) {
        scheduling.executeSync(plugin, new Location(world, x, y, z), () -> {
            RuntimeBlocks blocks = snapshot().blocks;
            Optional<RuntimeBlock> def = blocks == null ? Optional.empty() : blocks.byId(blockId);
            if (def.isEmpty()) {
                status.accept("unknown-block " + blockId);
                return;
            }
            place(world.getBlockAt(x, y, z), def.get());
            status.accept("placed " + blockId + " " + world.getName() + " " + x + " " + y + " " + z);
        });
    }

    /** Removes whatever occupies the coordinates (operator removal, no drops). */
    public void devRemove(World world, int x, int y, int z, Consumer<String> status) {
        scheduling.executeSync(plugin, new Location(world, x, y, z), () -> {
            Block block = world.getBlockAt(x, y, z);
            Optional<RuntimeBlock> found = identify(block);
            if (found.isEmpty()) {
                status.accept("not-custom " + world.getName() + " " + x + " " + y + " " + z);
                return;
            }
            remove(block, found.get(), false);
            status.accept("removed " + found.get().id() + " " + world.getName() + " " + x + " " + y + " " + z);
        });
    }

    public void devInspect(World world, int x, int y, int z, Consumer<String> status) {
        scheduling.executeSync(plugin, new Location(world, x, y, z), () -> {
            Block block = world.getBlockAt(x, y, z);
            Optional<RuntimeBlock> found = identify(block);
            String slot = switch (block.getType()) {
                case NOTE_BLOCK, TRIPWIRE, CHORUS_PLANT -> Integer.toString(decodedSlot(block));
                default -> "-";
            };
            status.accept(found.isEmpty()
                    ? "none material=" + block.getType().name().toLowerCase(Locale.ROOT)
                            + " rawSlot=" + slot + " " + describe(block)
                    : found.get().id() + " technique=" + found.get().technique().name()
                            .toLowerCase(Locale.ROOT) + " slot=" + found.get().slot()
                            + " material=" + block.getType().name().toLowerCase(Locale.ROOT)
                            + " rawSlot=" + slot + " " + describe(block));
        });
    }

    /**
     * Validates every indexed placement on its own region thread: stale index
     * entries, orphaned ids (MTR-0026), state/PDC drift. Without {@code fix}
     * nothing is mutated — report plus targeted client resync (the
     * keep-flagged default). With {@code fix}, a drifted placement's host
     * state is rewritten from its chunk record: the record wins as an
     * explicit operator action, so the position renders and identifies as
     * its recorded definition again.
     */
    public void devSweep(boolean fix, Consumer<String> status) {
        Map<PlacementIndex.Position, String> entries = index.snapshot();
        AtomicInteger remaining = new AtomicInteger(entries.size());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();
        AtomicInteger orphans = new AtomicInteger();
        AtomicInteger drifted = new AtomicInteger();
        AtomicInteger unloaded = new AtomicInteger();
        Runnable summarize = () -> status.accept("indexed=" + entries.size() + " ok=" + ok.get()
                + " stale=" + stale.get() + " orphans=" + orphans.get()
                + " drifted=" + drifted.get() + " unloaded=" + unloaded.get() + (fix ? " fixed" : ""));
        if (entries.isEmpty()) {
            summarize.run();
            return;
        }
        for (var entry : entries.entrySet()) {
            PlacementIndex.Position position = entry.getKey();
            World world = org.bukkit.Bukkit.getWorld(position.world());
            if (world == null) {
                unloaded.incrementAndGet();
                completeIfDone(remaining, summarize);
                continue;
            }
            scheduling.executeSync(plugin, new Location(world, position.x(), position.y(), position.z()), () -> {
                validatePosition(world, position, entry.getValue(), fix, ok, stale, orphans, drifted, unloaded);
                completeIfDone(remaining, summarize);
            });
        }
    }

    private static void completeIfDone(AtomicInteger remaining, Runnable summarize) {
        if (remaining.decrementAndGet() == 0) {
            summarize.run();
        }
    }

    private void validatePosition(
            World world,
            PlacementIndex.Position position,
            String indexedId,
            boolean fix,
            AtomicInteger ok,
            AtomicInteger stale,
            AtomicInteger orphans,
            AtomicInteger drifted,
            AtomicInteger unloaded) {
        if (!world.isChunkLoaded(position.x() >> 4, position.z() >> 4)) {
            unloaded.incrementAndGet();
            return;
        }
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        Optional<NamespacedId> recorded =
                ChunkBlocks.idAt(block.getChunk(), position.x(), position.y(), position.z());
        if (recorded.isEmpty() || !recorded.get().toString().equals(indexedId)) {
            stale.incrementAndGet();
            if (fix) {
                index.remove(position);
                if (recorded.isPresent()) {
                    index.add(position, recorded.get().toString());
                }
            }
            return;
        }
        NamespacedId id = NamespacedId.parse(indexedId);
        Optional<RuntimeBlock> def = snapshot().blocks.byId(id);
        if (def.isEmpty()) {
            orphans.incrementAndGet();
            reportLimited("orphan:" + id, DiagnosticCodes.ORPHANED_PLACEMENT, Severity.INFO,
                    "sweep: placement at " + world.getName() + " " + position.x() + " " + position.y()
                            + " " + position.z() + " references unknown definition " + id);
            return;
        }
        if (def.get().allocatesState()) {
            Optional<RuntimeBlock> decoded =
                    snapshot().blocks.bySlot(hostKey(block.getType()), decodedSlot(block));
            if (decoded.isEmpty() || !decoded.get().id().equals(id)) {
                drifted.incrementAndGet();
                reportLimited("drift:" + position.world() + "|" + position.x() + "," + position.y()
                                + "," + position.z(),
                        DiagnosticCodes.BLOCK_DATA_MISMATCH, Severity.WARN,
                        "sweep: placement at " + world.getName() + " " + position.x() + " "
                                + position.y() + " " + position.z() + " records " + id
                                + " but its state decodes to "
                                + decoded.map(RuntimeBlock::id).map(NamespacedId::toString)
                                        .orElse("nothing")
                                + "; the chunk record wins, state " + (fix ? "rewritten" : "resynced"));
                if (fix) {
                    repair(block, def.get());
                } else {
                    resync(block, def.get());
                }
                return;
            }
        }
        ok.incrementAndGet();
    }

    /**
     * Server-side drift repair ({@code dev sweep --fix}): rewrites the host
     * state from the record so the world matches what the chunk PDC says
     * again. The real block update reaches nearby clients on its own — no
     * targeted resync needed.
     */
    private void repair(Block block, RuntimeBlock def) {
        try {
            block.setBlockData(encode(def), false);
        } catch (RuntimeException e) {
            reportLimited("repair:" + posKey(block), DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                    "repair failed at " + describe(block) + ": " + e.getMessage());
        }
    }

    /** Targeted client resync: only players near the affected position. */
    private void resync(Block block, RuntimeBlock def) {
        try {
            BlockData data = encode(def);
            for (Player player : block.getWorld().getPlayers()) {
                if (player.getWorld().equals(block.getWorld())
                        && player.getLocation().distanceSquared(block.getLocation()) <= 48 * 48) {
                    player.sendBlockChange(block.getLocation(), data);
                }
            }
        } catch (RuntimeException e) {
            reportLimited("resync:" + posKey(block), DiagnosticCodes.ACTION_FAILED, Severity.WARN,
                    "resync failed at " + describe(block) + ": " + e.getMessage());
        }
    }

    // --- helpers -----------------------------------------------------------------

    private static String hostKey(Material type) {
        return type.name().toLowerCase(Locale.ROOT);
    }

    /** Any material that may carry a placement: state hosts, shaped hosts, technical hosts. */
    private boolean isHostMaterial(Material type) {
        return STATE_HOSTS.contains(type)
                || SHAPED_HOSTS.contains(type)
                || snapshot().technicalHosts.contains(type.name().toLowerCase(Locale.ROOT));
    }

    private static PlacementIndex.Position positionOf(Block block) {
        return new PlacementIndex.Position(
                block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    private static String describe(Block block) {
        return block.getWorld().getName() + " " + block.getX() + " " + block.getY() + " " + block.getZ();
    }

    private static String posKey(Block block) {
        return block.getWorld().getName() + "|" + block.getX() + "|" + block.getY() + "|" + block.getZ();
    }

    private void reportLimited(String key, DiagnosticCode code, Severity severity, String message) {
        long now = System.currentTimeMillis();
        Long last = lastReport.get(key);
        if (last != null && now - last < 1000) {
            return;
        }
        if (lastReport.size() > 4096) {
            lastReport.clear();
        }
        lastReport.put(key, now);
        diagnostics.publish(new Diagnostic(code, severity, message, null, null, null));
    }
}
