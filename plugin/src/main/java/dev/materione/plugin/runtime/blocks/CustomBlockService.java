package dev.materione.plugin.runtime.blocks;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.core.blocks.BlockStateSpaces;
import dev.materione.api.diagnostics.DiagnosticCode;
import dev.materione.core.diagnostics.DiagnosticCodes;
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
        place(event.getBlockPlaced(), def.get(), orientationOf(event.getPlayer()));
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.PLACE, null, event.getPlayer(),
                event.getBlockPlaced().getLocation(), def.get().id(),
                def.get().technique().name().toLowerCase(Locale.ROOT), null);
    }

    /**
     * The functional orientation for shaped techniques: stairs back onto the
     * placer, half/type follow the exact hit point (vanilla convention).
     * State-space techniques ignore it.
     */
    private Orientation orientationOf(Player player) {
        BlockFace facing = player.getFacing().getOppositeFace();
        try {
            org.bukkit.util.RayTraceResult hit = player.rayTraceBlocks(6.0, FluidCollisionMode.NEVER);
            if (hit != null && hit.getHitPosition() != null) {
                double y = hit.getHitPosition().getY();
                return new Orientation(facing, y - Math.floor(y) >= 0.5);
            }
        } catch (RuntimeException ignored) {
            // fall through to the default half
        }
        return new Orientation(facing, false);
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
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
        if (found.isEmpty()) {
            return;
        }
        RuntimeBlock def = found.get();
        behavior.fireBlockEvent(dev.materione.behavior.rule.EventTypes.INTERACT,
                right ? "right" : "left", event.getPlayer(), block.getLocation(), def.id(),
                def.technique().name().toLowerCase(Locale.ROOT), event);
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
     * entries, orphaned ids (MTR-0026), state/PDC drift with targeted client
     * resync. Never mutates world blocks — the keep-flagged default.
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
                                + "; the chunk record wins, state resynced");
                resync(block, def.get());
                return;
            }
        }
        ok.incrementAndGet();
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
