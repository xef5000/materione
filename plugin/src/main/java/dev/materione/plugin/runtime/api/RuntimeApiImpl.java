package dev.materione.plugin.runtime.api;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.runtime.RuntimeApi;
import dev.materione.api.scheduling.SchedulingGateway;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.pipeline.PipelineRunner;
import dev.materione.core.pipeline.graph.ContentGraph;
import dev.materione.core.runtime.ContentRuntime;
import dev.materione.core.runtime.RuntimeBlocks;
import dev.materione.core.runtime.RuntimeEffects;
import dev.materione.core.runtime.RuntimeFurnitures;
import dev.materione.core.runtime.RuntimeGlyphs;
import dev.materione.core.runtime.RuntimeItem;
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.gui.GuiRuntime;
import dev.materione.plugin.items.Give;
import dev.materione.plugin.items.ItemIdResolver;
import dev.materione.plugin.runtime.PipelineService;
import dev.materione.plugin.runtime.blocks.CustomBlockService;
import dev.materione.plugin.runtime.furniture.FurnitureService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * The {@link RuntimeApi} implementation: catalog queries read the live
 * shadow-swapped registries (the last successful apply's view), world
 * operations bridge the block/furniture dev surfaces with their id-level
 * fallbacks (one id surface for blocks and furniture — the {@code /materione
 * dev} semantics, now typed), and everything that touches a world or player
 * hops through the scheduling gateway (D8).
 */
public final class RuntimeApiImpl implements RuntimeApi {

    private final Plugin plugin;
    private final SchedulingGateway scheduling;
    private final PipelineService pipeline;
    private final java.util.function.Supplier<CustomBlockService> customBlocks;
    private final java.util.function.Supplier<FurnitureService> furniture;
    private final GuiRuntime guis;

    /**
     * The block/furniture services arrive as suppliers: they are wired in a
     * bootstrap phase after the API (commands phase), and the runtime api is
     * assembled at content-load time. Null from a supplier means "not wired" —
     * world operations fail soft with a diagnostic.
     */
    public RuntimeApiImpl(
            Plugin plugin,
            SchedulingGateway scheduling,
            PipelineService pipeline,
            java.util.function.Supplier<CustomBlockService> customBlocks,
            java.util.function.Supplier<FurnitureService> furniture) {
        this(plugin, scheduling, pipeline, customBlocks, furniture,
                new GuiRuntime(plugin, scheduling,
                        () -> pipeline == null ? null : pipeline.runtime().registries()));
    }

    /**
     * As above, with the GUI runtime the host also hooks to the live swap (gui.md §5): the
     * same instance must serve the api and the refresh, so the host builds it.
     */
    public RuntimeApiImpl(
            Plugin plugin,
            SchedulingGateway scheduling,
            PipelineService pipeline,
            java.util.function.Supplier<CustomBlockService> customBlocks,
            java.util.function.Supplier<FurnitureService> furniture,
            GuiRuntime guis) {
        this.plugin = plugin;
        this.scheduling = scheduling;
        this.pipeline = pipeline;
        this.customBlocks = customBlocks;
        this.furniture = furniture;
        this.guis = java.util.Objects.requireNonNull(guis, "guis");
    }

    private CustomBlockService blockService() {
        return customBlocks == null ? null : customBlocks.get();
    }

    private FurnitureService furnitureService() {
        return furniture == null ? null : furniture.get();
    }

    // --- catalog queries ----------------------------------------------------

    @Override
    public List<ItemView> items() {
        List<ItemView> out = new ArrayList<>();
        RuntimeRegistries registries = liveRegistries();
        if (registries == null) {
            return out;
        }
        for (RuntimeItem item : registries.items()) {
            out.add(new ItemView(
                    item.id(), item.material(), item.modelId(), item.displayName(),
                    item.lore(), item.synthetic()));
        }
        return out;
    }

    @Override
    public Optional<ItemView> item(NamespacedId id) {
        return items().stream().filter(item -> item.id().equals(id)).findFirst();
    }

    @Override
    public Optional<NamespacedId> resolveItem(String query) {
        RuntimeRegistries registries = liveRegistries();
        if (registries == null) {
            return Optional.empty();
        }
        ItemIdResolver.Result result = ItemIdResolver.resolve(query, registries.items());
        return result.status() == ItemIdResolver.Status.RESOLVED
                ? Optional.of(result.id())
                : Optional.empty();
    }

    @Override
    public List<BlockView> blocks() {
        List<BlockView> out = new ArrayList<>();
        RuntimeBlocks view = view(RuntimeBlocks.KEY);
        if (view == null) {
            return out;
        }
        for (NamespacedId id : view.ids()) {
            view.byId(id).ifPresent(block -> out.add(new BlockView(
                    block.id(), block.technique().name().toLowerCase(Locale.ROOT),
                    block.host(), block.slot())));
        }
        return out;
    }

    @Override
    public Optional<BlockView> block(NamespacedId id) {
        return blocks().stream().filter(block -> block.id().equals(id)).findFirst();
    }

    @Override
    public List<FurnitureView> furnitures() {
        List<FurnitureView> out = new ArrayList<>();
        RuntimeFurnitures view = view(RuntimeFurnitures.KEY);
        if (view == null) {
            return out;
        }
        for (var entry : view.all()) {
            out.add(new FurnitureView(entry.id(), entry.modelRef(), entry.displayName()));
        }
        return out;
    }

    @Override
    public Optional<FurnitureView> furniture(NamespacedId id) {
        return furnitures().stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    @Override
    public List<GlyphView> glyphs() {
        List<GlyphView> out = new ArrayList<>();
        RuntimeGlyphs view = view(RuntimeGlyphs.KEY);
        if (view == null) {
            return out;
        }
        for (RuntimeGlyphs.Entry entry : view.all()) {
            out.add(new GlyphView(
                    entry.id(), entry.fontKey(), entry.codepoints(), entry.permission()));
        }
        return out;
    }

    @Override
    public List<EffectView> effects() {
        List<EffectView> out = new ArrayList<>();
        RuntimeEffects view = view(RuntimeEffects.KEY);
        if (view == null) {
            return out;
        }
        for (RuntimeEffects.Entry entry : view.all()) {
            out.add(new EffectView(entry.id(), entry.kind()));
        }
        return out;
    }

    @Override
    public List<NamespacedId> sounds() {
        return graphType("sound");
    }

    @Override
    public List<NamespacedId> paintings() {
        return graphType("painting");
    }

    @Override
    public List<RecipeView> recipes() {
        List<RecipeView> out = new ArrayList<>();
        RuntimeRegistries registries = liveRegistries();
        if (registries == null) {
            return out;
        }
        for (var recipe : registries.recipes()) {
            out.add(new RecipeView(recipe.id(), recipe.kind()));
        }
        return out;
    }

    @Override
    public ItemRuntimeApi itemsOps() {
        return new ItemOps();
    }

    @Override
    public WorldRuntimeApi world() {
        return new WorldOps();
    }

    @Override
    public MediaRuntimeApi media() {
        return new MediaOps();
    }

    @Override
    public GuiRuntimeApi gui() {
        return guis;
    }

    // --- item operations ----------------------------------------------------

    private final class ItemOps implements ItemRuntimeApi {

        @Override
        public ApiResult<ItemStack> buildItemStack(NamespacedId id, int amount) {
            RuntimeRegistries registries = liveRegistries();
            if (registries == null) {
                return ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED, "no content is loaded yet");
            }
            RuntimeItem item = registries.item(id).orElse(null);
            if (item == null) {
                return ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "no live item matches '" + id + "'");
            }
            ItemStack stack = dev.materione.platform.items.MaterioneStacks.build(
                    dev.materione.platform.items.MaterioneStacks.specOf(item));
            stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
            return ApiResult.ok(stack);
        }

        @Override
        public Optional<NamespacedId> identifyItemStack(ItemStack stack) {
            return dev.materione.platform.items.MaterioneStacks.identityOf(stack);
        }

        @Override
        public CompletableFuture<ApiResult<Void>> giveItem(
                ActorContext actor, NamespacedId id, UUID targetPlayer, int amount) {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(id, "id");
            CompletableFuture<ApiResult<Void>> future = new CompletableFuture<>();
            Player player = onlinePlayer(targetPlayer);
            if (player == null) {
                future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "player is not online"));
                return future;
            }
            ApiResult<ItemStack> built = buildItemStack(id, 1);
            if (!built.ok()) {
                future.complete(ApiResult.fail(built.diagnostics()));
                return future;
            }
            scheduling.executeEntity(plugin, player, () -> {
                try {
                    Give.deliver(player, built.value(), Give.clampAmount(String.valueOf(amount)));
                    future.complete(ApiResult.ok(null));
                } catch (RuntimeException e) {
                    future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            "give failed: " + e.getMessage()));
                }
            });
            return future;
        }
    }

    // --- world operations ---------------------------------------------------

    private final class WorldOps implements WorldRuntimeApi {

        @Override
        public CompletableFuture<ApiResult<Void>> placeBlock(
                ActorContext actor, NamespacedId blockId, WorldPosition pos) {
            World world = worldOf(pos);
            CustomBlockService blocks = blockService();
            FurnitureService furnitures = furnitureService();
            if (world == null || blocks == null) {
                return CompletableFuture.completedFuture(unavailable(world, pos));
            }
            // one id surface: unknown to the block runtime → furniture runtime
            return statusFuture(
                    status -> blocks.devPlace(world, pos.x(), pos.y(), pos.z(), blockId, status),
                    status -> status.startsWith("unknown-block") && furnitures != null,
                    status -> furnitures.devPlace(world, pos.x(), pos.y(), pos.z(), blockId, status),
                    "placed");
        }

        @Override
        public CompletableFuture<ApiResult<Void>> removeBlock(ActorContext actor, WorldPosition pos) {
            World world = worldOf(pos);
            CustomBlockService blocks = blockService();
            FurnitureService furnitures = furnitureService();
            if (world == null || blocks == null) {
                return CompletableFuture.completedFuture(unavailable(world, pos));
            }
            return statusFuture(
                    status -> blocks.devRemove(world, pos.x(), pos.y(), pos.z(), status),
                    status -> status.startsWith("not-custom") && furnitures != null,
                    status -> furnitures.devRemove(world, pos.x(), pos.y(), pos.z(), status),
                    "removed");
        }

        @Override
        public CompletableFuture<ApiResult<Void>> placeFurniture(
                ActorContext actor, NamespacedId furnitureId, WorldLocation location) {
            WorldPosition pos = location.position();
            World world = worldOf(pos);
            FurnitureService furnitures = furnitureService();
            if (world == null || furnitures == null) {
                return CompletableFuture.completedFuture(unavailable(world, pos));
            }
            // furniture places with yaw snap; the dev surface places axis-aligned
            return statusFuture(
                    status -> furnitures.devPlace(world, pos.x(), pos.y(), pos.z(), furnitureId, status),
                    status -> false, null, "placed");
        }

        @Override
        public CompletableFuture<ApiResult<Void>> removeFurniture(ActorContext actor, WorldPosition pos) {
            World world = worldOf(pos);
            FurnitureService furnitures = furnitureService();
            if (world == null || furnitures == null) {
                return CompletableFuture.completedFuture(unavailable(world, pos));
            }
            return statusFuture(
                    status -> furnitures.devRemove(world, pos.x(), pos.y(), pos.z(), status),
                    status -> false, null, "removed");
        }

        @Override
        public CompletableFuture<ApiResult<PlacementInspection>> inspectAt(WorldPosition pos) {
            World world = worldOf(pos);
            CustomBlockService blocks = blockService();
            FurnitureService furnitures = furnitureService();
            if (world == null || blocks == null) {
                return CompletableFuture.completedFuture(unavailable(world, pos));
            }
            CompletableFuture<ApiResult<PlacementInspection>> future = new CompletableFuture<>();
            List<String> details = new ArrayList<>();
            blocks.devInspect(world, pos.x(), pos.y(), pos.z(), blockStatus -> {
                details.add(blockStatus);
                boolean blockFound = !blockStatus.startsWith("none");
                if (!blockFound && furnitures != null) {
                    furnitures.devInspect(world, pos.x(), pos.y(), pos.z(), furnitureStatus -> {
                        details.add(furnitureStatus);
                        completeInspection(future, furnitureStatus, details);
                    });
                } else {
                    completeInspection(future, blockStatus, details);
                }
            });
            return future;
        }

        @Override
        public CompletableFuture<ApiResult<OrphanSweep>> sweepOrphans(ActorContext actor, boolean fix) {
            CustomBlockService blocks = blockService();
            if (blocks == null) {
                return CompletableFuture.completedFuture(ApiResult.fail(
                        DiagnosticCodes.PIPELINE_STAGE_FAILED,
                        "custom blocks are not wired yet"));
            }
            CompletableFuture<ApiResult<OrphanSweep>> future = new CompletableFuture<>();
            List<String> lines = new ArrayList<>();
            blocks.devSweep(fix, status -> {
                lines.add(status);
                // the sweep ends with its summary line ("indexed=… ok=… orphans=…")
                if (status.startsWith("indexed=")) {
                    future.complete(ApiResult.ok(
                            new OrphanSweep(fix, List.copyOf(lines))));
                }
            });
            return future;
        }

        private void completeInspection(
                CompletableFuture<ApiResult<PlacementInspection>> future,
                String lastStatus, List<String> details) {
            NamespacedId blockId = idPrefix(details, "block ");
            NamespacedId furnitureId = idPrefix(details, "furniture ");
            boolean orphan = lastStatus.contains("orphan") || lastStatus.contains("stale");
            future.complete(ApiResult.ok(new PlacementInspection(
                    blockId, furnitureId, orphan, List.copyOf(details))));
        }

        /** Extracts a {@code <prefix><ns:id>} mention from a status line. */
        private NamespacedId idPrefix(List<String> details, String marker) {
            for (String line : details) {
                int at = line.indexOf(marker);
                if (at >= 0) {
                    String rest = line.substring(at + marker.length()).trim();
                    String id = rest.split("[\\s,]+")[0];
                    if (id.contains(":")) {
                        return NamespacedId.parse(id);
                    }
                }
            }
            return null;
        }

        private World worldOf(WorldPosition pos) {
            return pos == null ? null : Bukkit.getWorld(pos.world());
        }

        private <T> ApiResult<T> unavailable(World world, WorldPosition pos) {
            String reason = world == null
                    ? "unknown world '" + (pos == null ? "?" : pos.world()) + "'"
                    : "world runtime is not wired yet";
            return ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE, reason);
        }
    }

    // --- media operations ---------------------------------------------------

    private final class MediaOps implements MediaRuntimeApi {

        @Override
        public Component formatText(String text) {
            return dev.materione.platform.text.GlyphText.resolve(text);
        }

        @Override
        public CompletableFuture<ApiResult<Void>> playSound(
                ActorContext actor, UUID playerId, String soundKey, float volume, float pitch) {
            CompletableFuture<ApiResult<Void>> future = new CompletableFuture<>();
            Player player = onlinePlayer(playerId);
            if (player == null) {
                future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "player is not online"));
                return future;
            }
            scheduling.executeEntity(plugin, player, () -> {
                player.getWorld().playSound(
                        player.getLocation(), soundKey, SoundCategory.MASTER, volume, pitch);
                future.complete(ApiResult.ok(null));
            });
            return future;
        }

        @Override
        public CompletableFuture<ApiResult<Void>> placePainting(
                ActorContext actor, UUID playerId, NamespacedId variantId) {
            CompletableFuture<ApiResult<Void>> future = new CompletableFuture<>();
            Player player = onlinePlayer(playerId);
            if (player == null) {
                future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "player is not online"));
                return future;
            }
            scheduling.executeEntity(plugin, player, () -> {
                try {
                    org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(
                            variantId.toString());
                    org.bukkit.Art variant = key == null ? null
                            : io.papermc.paper.registry.RegistryAccess.registryAccess()
                                    .getRegistry(io.papermc.paper.registry.RegistryKey.PAINTING_VARIANT)
                                    .get(key);
                    if (variant == null) {
                        future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                                "painting variant '" + variantId + "' is not registered — variants"
                                        + " arrive with the content datapack/registry at boot"));
                        return;
                    }
                    var hit = player.rayTraceBlocks(32);
                    if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null
                            || hit.getHitBlockFace().getModY() != 0) {
                        future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                                "player must look at a wall within 32 blocks"));
                        return;
                    }
                    org.bukkit.block.BlockFace face = hit.getHitBlockFace();
                    org.bukkit.Location spot = hit.getHitBlock().getRelative(face).getLocation()
                            .add(0.5, 0.5, 0.5);
                    var painting = player.getWorld().spawn(spot, org.bukkit.entity.Painting.class,
                            p -> p.setFacingDirection(face, true));
                    if (!painting.setArt(variant)) {
                        painting.remove();
                        future.complete(ApiResult.fail(DiagnosticCodes.UNRESOLVED_REFERENCE,
                                "variant '" + variantId + "' needs clear wall space"));
                        return;
                    }
                    future.complete(ApiResult.ok(null));
                } catch (RuntimeException e) {
                    future.complete(ApiResult.fail(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                            "painting placement failed: " + e.getMessage()));
                }
            });
            return future;
        }
    }

    // --- helpers ------------------------------------------------------------

    private RuntimeRegistries liveRegistries() {
        ContentRuntime runtime = pipeline == null ? null : pipeline.runtime();
        return runtime == null ? null : runtime.registries();
    }

    /** Player lookup that fails soft when no server is up (tests, early bootstrap). */
    private static Player onlinePlayer(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        try {
            return Bukkit.getPlayer(uuid);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <V> V view(Class<V> key) {
        RuntimeRegistries registries = liveRegistries();
        return registries == null ? null : registries.view(key);
    }

    /** Pack-side content types (sounds, paintings) list from the last graph. */
    private List<NamespacedId> graphType(String contentType) {
        PipelineRunner runner = pipeline == null ? null : pipeline.runner();
        ContentGraph graph = runner == null ? null : runner.lastGraph();
        List<NamespacedId> out = new ArrayList<>();
        if (graph != null) {
            for (ContentGraph.Node node : graph.nodes()) {
                if (node.contentType().toLowerCase(Locale.ROOT).equals(contentType)) {
                    out.add(node.id());
                }
            }
        }
        return out;
    }

    /**
     * Wraps a {@code Consumer<String>} dev call into an ok/fail future by
     * status prefix; {@code fallbackWhen}/{@code fallback} reproduce the dev
     * commands' cross-runtime fallback (unknown block → furniture runtime).
     */
    private CompletableFuture<ApiResult<Void>> statusFuture(
            java.util.function.Consumer<java.util.function.Consumer<String>> call,
            java.util.function.Predicate<String> fallbackWhen,
            java.util.function.Consumer<java.util.function.Consumer<String>> fallback,
            String okPrefix) {
        CompletableFuture<ApiResult<Void>> future = new CompletableFuture<>();
        call.accept(status -> {
            if (fallbackWhen.test(status)) {
                fallback.accept(second -> future.complete(resultOf(second, okPrefix)));
                return;
            }
            future.complete(resultOf(status, okPrefix));
        });
        return future;
    }

    private static ApiResult<Void> resultOf(String status, String okPrefix) {
        return status.startsWith(okPrefix)
                ? ApiResult.ok(null)
                : ApiResult.fail(Diagnostic.error(DiagnosticCodes.PIPELINE_STAGE_FAILED, status));
    }
}
