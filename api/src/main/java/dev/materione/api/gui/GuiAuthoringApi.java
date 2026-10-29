package dev.materione.api.gui;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.registry.SelfDescription;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The GUI builder's typed surface (api.md §3.3, gui.md §7 and §10): everything the web
 * studio does to a GUI, through one implementation. A GUI is a YAML file under
 * {@code guis/} with one GUI resource keyed by its id; this API works on the file's single
 * GUI (a file holding several is refused).
 *
 * <p>Writes are revision-checked and all-or-none, and go through {@link AuthoringApi}: a
 * layer edit is one {@code setFields} transaction, a customization one {@code bulk}. Reads
 * and {@link #render}/{@link #validate} never write. {@link #validate} and {@link #render}
 * run the pack compiler's own steps over the draft, so they report exactly what a compile
 * would (gui.md §4).
 *
 * <p>Fail-soft: a domain problem returns {@link ApiResult} diagnostics; nothing throws for
 * content.
 */
public interface GuiAuthoringApi {

    /** The {@code gui-components} registry as it stands now: built-in and addon, in registry order. */
    List<GuiComponentDescriptor> components();

    /** The GUI in {@code pack}/{@code path}, typed; the document's own fields (templates are not merged). */
    ApiResult<GuiDocumentView> get(String pack, String path);

    /**
     * Applies {@code edits} in order, all or none, at {@code expectedRevision}. An edit that
     * names an unknown component, a duplicate or unknown layer id, or a bad screen size refuses
     * the whole request. Warnings for layers the compiler will skip come back as diagnostics on
     * the successful result.
     */
    ApiResult<GuiDocumentView> edit(ActorContext actor, GuiEditRequest request);

    /**
     * Gives an item layer its own item at {@code items/gui/<gui-path>/<id>.yml} and points the
     * layer's {@code item:} at it, in one transaction with the GUI's revision check. A vanilla
     * base becomes a {@code material:} item; a Materione item is copied (items cannot be
     * templates yet, gui.md §10 #1).
     */
    ApiResult<CustomizeItemResult> customizeItem(ActorContext actor, CustomizeItemRequest request);

    /**
     * The flattened background PNG and the compiled layer order of a saved GUI or an unsaved
     * draft. Pure: nothing is written and no allocation is kept.
     */
    ApiResult<GuiRender> render(GuiRenderRequest request);

    /**
     * The diagnostics the compile would emit for a saved GUI or an unsaved draft (MTR-0052 per
     * skipped layer, parse failures, background exhaustion). Nothing is written.
     */
    ApiResult<List<Diagnostic>> validate(GuiValidateRequest request);

    // ------------------------------------------------------------------

    /** One layer as the typed view reads it. Absent common fields take their defaults. */
    record Layer(
            String id,
            String type,
            String name,
            boolean visible,
            boolean locked,
            Integer opacity,
            Map<String, Object> parameters) {

        public Layer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            parameters = Collections.unmodifiableMap(new LinkedHashMap<>(parameters == null ? Map.of() : parameters));
        }
    }

    /**
     * A GUI as the typed view carries it: {@code pack}, {@code path} and {@code id} name it,
     * {@code title} and {@code rows} are what the file says (null when absent or not valid), and
     * {@code layers} are its mapping layers in order. {@code revision} is the file's revision.
     */
    record GuiDocumentView(
            String pack,
            String path,
            String id,
            String title,
            Integer rows,
            int revision,
            List<Layer> layers) {

        public GuiDocumentView {
            Objects.requireNonNull(pack, "pack");
            Objects.requireNonNull(id, "id");
            layers = layers == null ? List.of() : List.copyOf(layers);
        }
    }

    /** One structural edit to a GUI's layers, or to its screen. Applied in list order. */
    sealed interface GuiEdit permits AddLayer, PatchLayer, RemoveLayers, ReorderLayers, SetScreen {}

    /**
     * Inserts a layer at {@code index} (0 is the bottom). {@code id} may be null: a unique id is
     * derived from the component name. {@code parameters} carries the layer's fields, common
     * ({@code name}, {@code visible}, {@code locked}, {@code opacity}) and the component's own.
     */
    record AddLayer(int index, String type, String id, Map<String, Object> parameters) implements GuiEdit {
        public AddLayer {
            parameters = ordered(parameters);
        }
    }

    /**
     * Sets and removes fields of one layer. {@code set} writes each field (a null value removes
     * it); {@code unset} removes the named fields. The layer's {@code id} cannot change, and its
     * {@code type} must stay a known component.
     */
    record PatchLayer(String id, Map<String, Object> set, List<String> unset) implements GuiEdit {
        public PatchLayer {
            set = ordered(set);
            unset = unset == null ? List.of() : List.copyOf(unset);
        }
    }

    /** Removes the named layers. Every id must exist. */
    record RemoveLayers(List<String> ids) implements GuiEdit {
        public RemoveLayers {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    /** Reorders the layers: {@code idsInOrder} must name every layer exactly once. */
    record ReorderLayers(List<String> idsInOrder) implements GuiEdit {
        public ReorderLayers {
            idsInOrder = idsInOrder == null ? List.of() : List.copyOf(idsInOrder);
        }
    }

    /** Sets the title and/or the rows (1 to 6); a null field is left as it is. */
    record SetScreen(String title, Integer rows) implements GuiEdit {}

    /** An immutable copy that keeps the caller's order: the order fields are written in. */
    private static Map<String, Object> ordered(Map<String, Object> source) {
        return source == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * {@link #edit}: the GUI's {@code pack}/{@code path} at {@code expectedRevision}, the edits in
     * order, and binaries committed in the same transaction ({@code assets} under {@code assets/},
     * for paint and image layers; an empty list for a layout-only edit).
     */
    record GuiEditRequest(
            String pack,
            String path,
            int expectedRevision,
            List<GuiEdit> edits,
            List<AuthoringApi.ModelAsset> assets) {

        public GuiEditRequest {
            edits = edits == null ? List.of() : List.copyOf(edits);
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    /**
     * {@link #customizeItem}: the GUI's {@code pack}/{@code path} at {@code expectedRevision} and
     * the item layer's id.
     */
    record CustomizeItemRequest(String pack, String path, int expectedRevision, String layerId) {}

    /**
     * What {@link #customizeItem} created: the item's id, its pack-relative path, the reference
     * the layer now holds ({@code pack:id}) and the GUI's new revision.
     */
    record CustomizeItemResult(String itemId, String itemPath, String reference, int revision) {}

    /**
     * Where a GUI being rendered or validated comes from:
     * <ul>
     *   <li>{@link Saved}: the file on disk;</li>
     *   <li>{@link Yaml}: unsaved YAML text of a GUI file, keyed by its id;</li>
     *   <li>{@link Typed}: an unsaved typed draft, in the shape {@link #get} returns.</li>
     * </ul>
     */
    sealed interface GuiSource permits Saved, Yaml, Typed {}

    /** A saved GUI file. */
    record Saved(String pack, String path) implements GuiSource {}

    /** Unsaved YAML text of a GUI file of {@code pack}. */
    record Yaml(String pack, String text) implements GuiSource {}

    /** An unsaved typed draft; its {@code pack} and {@code id} name the GUI. */
    record Typed(GuiDocumentView document) implements GuiSource {}

    /**
     * {@link #render}: the GUI to draw and the draft binaries it refers to ({@code assets/textures/}
     * PNGs, read in place of the saved ones). An empty list renders the saved binaries.
     */
    record GuiRenderRequest(GuiSource source, List<AuthoringApi.ModelAsset> assets) {
        public GuiRenderRequest {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    /** {@link #validate}: the same inputs as {@link GuiRenderRequest}. */
    record GuiValidateRequest(GuiSource source, List<AuthoringApi.ModelAsset> assets) {
        public GuiValidateRequest {
            assets = assets == null ? List.of() : List.copyOf(assets);
        }
    }

    /**
     * One layer as the compiler placed it. {@code target} is null for a layer that did not parse.
     * {@code compiledRank} is its 0-based position in the compiled order of the visible layers
     * (raster, then text, then slot items, each in list order), or -1 when hidden. {@code reason}
     * says why a layer is {@code skipped}.
     */
    record LayerRender(String id, RenderTarget target, int compiledRank, boolean skipped, String reason) {}

    /**
     * {@link #render} outcome: the canvas size in GUI pixels, the background as a PNG (the bytes
     * the pack ships), the layers in list order and the compile's diagnostics.
     */
    record GuiRender(
            int width,
            int height,
            byte[] background,
            List<LayerRender> layers,
            List<Diagnostic> diagnostics) {

        public GuiRender {
            background = background == null ? new byte[0] : background.clone();
            layers = layers == null ? List.of() : List.copyOf(layers);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }

        @Override
        public byte[] background() {
            return background.clone();
        }
    }

    /** The registered component as the studio's inspector needs it; built from the registry entry. */
    record GuiComponentDescriptor(
            NamespacedId id,
            RenderTarget target,
            SelfDescription description,
            String registeredBy) {}
}
