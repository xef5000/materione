package dev.materione.content.gui;

import dev.materione.api.gui.GuiAuthoringApi.AddLayer;
import dev.materione.api.gui.GuiAuthoringApi.GuiEdit;
import dev.materione.api.gui.GuiAuthoringApi.SetOpen;
import dev.materione.api.gui.GuiAuthoringApi.PatchLayer;
import dev.materione.api.gui.GuiAuthoringApi.RemoveLayers;
import dev.materione.api.gui.GuiAuthoringApi.ReorderLayers;
import dev.materione.api.gui.GuiAuthoringApi.SetScreen;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The structural edits of a GUI's layers (gui.md §7, §10 #9), as a pure function over the
 * authored fields. Edits apply in order to a working copy; the first refused edit throws, and
 * the caller writes nothing. Layer maps are kept as authored: a field an edit does not name is
 * carried over unchanged, unknown keys included.
 *
 * <p>Refusals are the ids and types the compiler would otherwise quarantine: an id that does not
 * match {@code [a-z0-9_]+} or is taken, a component the registry does not know, a screen outside
 * 1 to 6 rows, a reorder that is not a permutation. Parameter values are not judged here: the
 * compiler skips a layer whose fields it cannot read and says so (MTR-0052), and the API reports
 * that as a warning.
 */
final class GuiLayerEdits {

    /** The authored fields after the edits. */
    record Result(String title, int rows, List<Map<String, Object>> layers) {}

    private static final Set<String> RESERVED = Set.of("id", "type");

    private final Predicate<String> knownComponent;

    /** @param knownComponent whether an authored {@code type:} names a registered component */
    GuiLayerEdits(Predicate<String> knownComponent) {
        this.knownComponent = knownComponent;
    }

    /**
     * Applies {@code edits} in order.
     *
     * @param title the authored title (may be null when absent)
     * @param rows the authored rows
     * @param layers the authored layer mappings, bottom to top; not modified
     * @throws IllegalArgumentException with the author-facing reason when an edit is refused
     */
    Result apply(String title, int rows, List<Map<String, Object>> layers, List<GuiEdit> edits) {
        String nextTitle = title;
        int nextRows = rows;
        var working = new ArrayList<Map<String, Object>>();
        var authored = new HashSet<String>();
        for (Map<String, Object> layer : layers) {
            String id = String.valueOf(layer.get("id"));
            if (!authored.add(id) && !edits.isEmpty()) {
                // the compiler keeps the first of two layers with one id; an edit cannot tell them apart
                throw new IllegalArgumentException("two layers are named '" + id + "'; fix the file before editing");
            }
            working.add(new LinkedHashMap<>(layer));
        }
        for (GuiEdit edit : edits) {
            switch (edit) {
                case AddLayer add -> add(working, add);
                case PatchLayer patch -> patch(working, patch);
                case RemoveLayers remove -> remove(working, remove);
                case ReorderLayers reorder -> reorder(working, reorder);
                case SetOpen ignored -> {
                    // the open: block is not a layer; GuiAuthoringApiImpl writes it as its own field
                }
                case SetScreen screen -> {
                    if (screen.title() != null) {
                        if (screen.title().isBlank()) {
                            throw new IllegalArgumentException("the title must not be blank");
                        }
                        nextTitle = screen.title();
                    }
                    if (screen.rows() != null) {
                        if (screen.rows() < 1 || screen.rows() > GuiType.MAX_ROWS) {
                            throw new IllegalArgumentException(
                                    "rows must be from 1 to " + GuiType.MAX_ROWS + ", not " + screen.rows());
                        }
                        nextRows = screen.rows();
                    }
                }
            }
        }
        return new Result(nextTitle, nextRows, working);
    }

    private void add(List<Map<String, Object>> working, AddLayer add) {
        if (add.index() < 0 || add.index() > working.size()) {
            throw new IllegalArgumentException(
                    "layer index " + add.index() + " is outside 0.." + working.size());
        }
        requireComponent(add.type());
        Set<String> taken = ids(working);
        String id = add.id() == null ? generatedId(add.type(), taken) : add.id();
        if (!GuiType.LAYER_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("layer id '" + id + "' must match [a-z0-9_]+");
        }
        if (taken.contains(id)) {
            throw new IllegalArgumentException("layer id '" + id + "' is already used in this GUI");
        }
        for (String reserved : RESERVED) {
            if (add.parameters().containsKey(reserved)) {
                throw new IllegalArgumentException("a new layer's fields may not set '" + reserved + "'");
            }
        }
        var layer = new LinkedHashMap<String, Object>();
        layer.put("id", id);
        layer.put("type", add.type());
        layer.putAll(add.parameters());
        working.add(add.index(), layer);
    }

    private void patch(List<Map<String, Object>> working, PatchLayer patch) {
        Map<String, Object> layer = find(working, patch.id());
        if (patch.set().containsKey("id")) {
            throw new IllegalArgumentException("a layer's id cannot change ('" + patch.id() + "')");
        }
        for (String unset : patch.unset()) {
            if (RESERVED.contains(unset)) {
                throw new IllegalArgumentException("'" + unset + "' cannot be removed from layer '" + patch.id() + "'");
            }
        }
        if (patch.set().containsKey("type")) {
            requireComponent(String.valueOf(patch.set().get("type")));
        }
        patch.set().forEach((field, value) -> {
            if (value == null) {
                layer.remove(field);
            } else {
                layer.put(field, value);
            }
        });
        for (String unset : patch.unset()) {
            layer.remove(unset);
        }
    }

    private void remove(List<Map<String, Object>> working, RemoveLayers remove) {
        Set<String> doomed = new HashSet<>();
        for (String id : remove.ids()) {
            find(working, id);
            if (!doomed.add(id)) {
                throw new IllegalArgumentException("layer '" + id + "' is named twice");
            }
        }
        working.removeIf(layer -> doomed.contains(layer.get("id")));
    }

    private void reorder(List<Map<String, Object>> working, ReorderLayers reorder) {
        if (reorder.idsInOrder().size() != working.size()) {
            throw new IllegalArgumentException(
                    "a reorder names every layer once: this GUI has " + working.size() + " layers");
        }
        var byId = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> layer : working) {
            byId.put(String.valueOf(layer.get("id")), layer);
        }
        var ordered = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (String id : reorder.idsInOrder()) {
            Map<String, Object> layer = byId.get(id);
            if (layer == null) {
                throw new IllegalArgumentException("no layer '" + id + "' in this GUI");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("layer '" + id + "' is named twice");
            }
            ordered.add(layer);
        }
        working.clear();
        working.addAll(ordered);
    }

    private void requireComponent(String type) {
        if (type == null || type.isBlank() || !knownComponent.test(type)) {
            throw new IllegalArgumentException("unknown component '" + type + "'");
        }
    }

    private static Map<String, Object> find(List<Map<String, Object>> working, String id) {
        for (Map<String, Object> layer : working) {
            if (id != null && id.equals(layer.get("id"))) {
                return layer;
            }
        }
        throw new IllegalArgumentException("no layer '" + id + "' in this GUI");
    }

    private static Set<String> ids(List<Map<String, Object>> working) {
        var ids = new HashSet<String>();
        for (Map<String, Object> layer : working) {
            ids.add(String.valueOf(layer.get("id")));
        }
        return ids;
    }

    /** A new layer's id: the component's own name (its last segment), made unique with a counter. */
    static String generatedId(String type, Set<String> taken) {
        String bare = type.substring(type.lastIndexOf(':') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (bare.isEmpty()) {
            bare = "layer";
        }
        String candidate = bare;
        for (int counter = 2; taken.contains(candidate); counter++) {
            candidate = bare + "_" + counter;
        }
        return candidate;
    }
}
