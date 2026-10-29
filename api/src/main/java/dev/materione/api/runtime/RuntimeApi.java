package dev.materione.api.runtime;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

/**
 * Live runtime and world operations (api.md §3.7, runtime-systems.md):
 * catalog queries over the shadow-swapped definitions, item stack
 * building/giving, block/furniture placement and inspection, and the media
 * surface (glyph text, sounds, paintings). Read queries reflect the last
 * successful apply; world mutations hop onto the correct scheduler (D8) —
 * they return futures, never block the caller thread.
 */
public interface RuntimeApi {

    // --- Live catalog queries (last successful apply's view) ---

    /** All live items. */
    List<ItemView> items();

    Optional<ItemView> item(NamespacedId id);

    /** Resolves a user query ({@code ruby}, {@code demo:ruby}) the way {@code /materione give} does. */
    Optional<NamespacedId> resolveItem(String query);

    List<BlockView> blocks();

    Optional<BlockView> block(NamespacedId id);

    List<FurnitureView> furnitures();

    Optional<FurnitureView> furniture(NamespacedId id);

    List<GlyphView> glyphs();

    List<EffectView> effects();

    /** Sound resources of the loaded packs (their live state is pack-side). */
    List<NamespacedId> sounds();

    /** Painting variants of the loaded packs (boot-registered or datapack). */
    List<NamespacedId> paintings();

    List<RecipeView> recipes();

    // --- Sub-surfaces ---

    ItemRuntimeApi itemsOps();

    WorldRuntimeApi world();

    MediaRuntimeApi media();

    /** Live GUI screens: the catalog and opening one for a player (gui.md §5). */
    GuiRuntimeApi gui();

    // ------------------------------------------------------------------

    /**
     * GUI screens (gui.md §5). The catalog reads the last successful apply's compiled GUIs;
     * opening one hops onto the viewer's region thread and fails soft when the GUI is not
     * live (unknown, or quarantined by its last compile) or the player is offline.
     */
    interface GuiRuntimeApi {

        /** Every live GUI, in registry order. */
        List<GuiView> guis();

        Optional<GuiView> gui(NamespacedId id);

        /**
         * Opens a live GUI for one online player, replacing whatever container the player has
         * open. Every click inside the GUI is cancelled in v1 (gui.md §5). Requires the
         * {@code materione.gui.open} node. The future completes when the window is open (or
         * immediately, with the reason, when the request is refused); it never blocks the caller.
         */
        CompletableFuture<ApiResult<Void>> open(ActorContext actor, NamespacedId guiId, UUID targetPlayer);

        /** The online player's name, or empty when that player is offline. */
        Optional<String> onlineName(UUID player);
    }

    /** One live GUI: its id, chest rows, plain title and how many slots it fills. */
    record GuiView(NamespacedId id, int rows, String accessibleTitle, int filledSlots) {}

    // ------------------------------------------------------------------

    /** Item stack operations. */
    interface ItemRuntimeApi {

        /** Builds an identified stack ({@code MaterioneStacks} semantics); fails soft on unknown ids. */
        ApiResult<ItemStack> buildItemStack(NamespacedId id, int amount);

        /** The custom identity carried by a stack, if it is Materione content. */
        Optional<NamespacedId> identifyItemStack(ItemStack stack);

        /**
         * Gives {@code amount} of the item to the player, splitting past
         * stack limits — Folia-safe via the scheduling gateway (D8).
         */
        CompletableFuture<ApiResult<Void>> giveItem(
                ActorContext actor, NamespacedId id, UUID targetPlayer, int amount);
    }

    /** World placement, inspection and orphan sweeps. */
    interface WorldRuntimeApi {

        CompletableFuture<ApiResult<Void>> placeBlock(ActorContext actor, NamespacedId blockId, WorldPosition pos);

        CompletableFuture<ApiResult<Void>> removeBlock(ActorContext actor, WorldPosition pos);

        CompletableFuture<ApiResult<Void>> placeFurniture(
                ActorContext actor, NamespacedId furnitureId, WorldLocation location);

        CompletableFuture<ApiResult<Void>> removeFurniture(ActorContext actor, WorldPosition pos);

        /** What sits at a position: custom block/furniture id, state, orphan status. */
        CompletableFuture<ApiResult<PlacementInspection>> inspectAt(WorldPosition pos);

        /** Scans loaded chunks for orphaned placements ({@code MTR-0026}); fixes them when asked. */
        CompletableFuture<ApiResult<OrphanSweep>> sweepOrphans(ActorContext actor, boolean fix);
    }

    /** Text, sound and painting operations. */
    interface MediaRuntimeApi {

        /** Resolves {@code <glyph:...>} and {@code <effect:...>} tags into an Adventure component. */
        Component formatText(String text);

        /**
         * Plays a custom or vanilla sound key to one player (or, with a null
         * player, at the console-observing server's discretion).
         */
        CompletableFuture<ApiResult<Void>> playSound(
                ActorContext actor, UUID player, String soundKey, float volume, float pitch);

        /** Places a custom painting for the targeted player (registry/datapack mode aware). */
        CompletableFuture<ApiResult<Void>> placePainting(ActorContext actor, UUID player, NamespacedId variantId);
    }

    // ------------------------------------------------------------------

    /** A world cell: world name plus block coordinates (transport-free). */
    record WorldPosition(String world, int x, int y, int z) {

        public WorldLocation atYaw(float yaw) {
            return new WorldLocation(this, yaw);
        }
    }

    /** A world cell plus facing (furniture places with rotation). */
    record WorldLocation(WorldPosition position, float yaw) {}

    /** One live item definition. */
    record ItemView(
            NamespacedId id,
            String material,
            NamespacedId model,
            String displayName,
            List<String> lore,
            boolean syntheticPlacementItem) {}

    /** One live custom block definition. */
    record BlockView(NamespacedId id, String technique, String host, int stateSlot) {}

    /** One live furniture definition. */
    record FurnitureView(NamespacedId id, String modelReference, String displayName) {}

    /** One live glyph: its font key and allocated codepoints (D7 table truth). */
    record GlyphView(NamespacedId id, String fontKey, int[] codepoints, String permission) {}

    /** One live text effect. */
    record EffectView(NamespacedId id, String kind) {}

    /** One live recipe. */
    record RecipeView(NamespacedId id, String kind) {}

    /** What {@link WorldRuntimeApi#inspectAt} found. */
    record PlacementInspection(
            NamespacedId blockId,
            NamespacedId furnitureId,
            boolean orphan,
            List<String> details) {}

    /** What {@link WorldRuntimeApi#sweepOrphans} did. */
    record OrphanSweep(boolean fixed, List<String> lines) {}
}
