package dev.materione.content.gui;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticTarget;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.Templates;
import dev.materione.core.document.LosslessYaml;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The GUI content type (gui.md §2–§4): parses {@code guis/*.yml} into an ordered
 * list of layers, each an instance of a component from the {@code gui-components}
 * registry. The file is keyed by id like every content type (master §7.2). One
 * bad layer is quarantined alone ({@link GuiDefinition#skipped()}, published as
 * {@code MTR-0052} through {@link #diagnose}); the rest of the GUI parses.
 *
 * <p>Compilation ({@link GuiCompiler}) flattens the raster layers, bakes the background
 * glyph, assembles the title and builds the slot table (gui.md §4).
 */
public final class GuiType implements ContentType {

    /** Chest rows: the vanilla container sizes are 1 to 6 rows. */
    static final int MAX_ROWS = 6;

    static final int MAX_OPACITY = 100;

    /** Layer ids are stable, lower-case names: {@code [a-z0-9_]+}. */
    static final Pattern LAYER_ID = Pattern.compile("[a-z0-9_]+");

    /** The namespace an unqualified component name aliases, as with mechanics. */
    static final String BUILTIN_NAMESPACE = "materione";

    /** Layer fields the gui type owns; everything else belongs to the component. */
    private static final Set<String> COMMON_FIELDS =
            Set.of("id", "type", "name", "visible", "locked", "opacity");

    private final Registry<GuiComponent> components;

    /**
     * @param components the {@code gui-components} registry layers resolve against
     */
    public GuiType(Registry<GuiComponent> components) {
        this.components = Objects.requireNonNull(components, "components");
    }

    @Override
    public String name() {
        return "gui";
    }

    @Override
    public String directory() {
        return "guis";
    }

    @Override
    public SelfDescription describe() {
        var layerFields = List.of(
                new ParameterSchema("id", ParameterType.STRING, true, null,
                        "Stable id, unique in this GUI ([a-z0-9_]+)", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Id", "identity", 0, false, "text", "", "frame")),
                new ParameterSchema("type", ParameterType.REFERENCE, true, null,
                        "The component this layer is: panel, slots, image, paint, button, text or item, "
                                + "or an addon's qualified name", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Component", "identity", 10, false, "reference", "gui-component", "panel")),
                new ParameterSchema("name", ParameterType.STRING, false, null,
                        "Display name in the layers panel", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Name", "identity", 20, false, "text", "", "Frame")),
                new ParameterSchema("visible", ParameterType.BOOLEAN, false, "true",
                        "Hidden layers stay in the file but are not compiled", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Visible", "identity", 30, false, "toggle", "", "true")),
                new ParameterSchema("locked", ParameterType.BOOLEAN, false, "false",
                        "Editor-only: the studio will not move or edit this layer", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Locked", "identity", 40, true, "toggle", "", "true")),
                new ParameterSchema("opacity", ParameterType.INTEGER, false, "100",
                        "Raster layers only: 0 is transparent, 100 is opaque", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Opacity", "appearance", 50, true, "number", "", "100")
                                .withRange(0.0, (double) MAX_OPACITY, "%")));
        var layers = new ParameterSchema("layers", ParameterType.LIST, false, null,
                "Ordered layers, bottom to top; each layer is one component instance",
                null, layerFields)
                .withEditor(new ParameterSchema.EditorMetadata(
                        "Layers", "layers", 0, false, "structured-list", "", ""));
        return new SelfDescription(
                "GUI",
                "A chest screen designed as ordered layers: panels, slot wells, images, paint, buttons, "
                        + "title text and items",
                List.of(
                        new ParameterSchema("title", ParameterType.STRING, true, null,
                                "Accessibility and fallback title (narration, title-less clients)", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Title", "identity", 0, false, "text", "", "Shop")),
                        new ParameterSchema("rows", ParameterType.INTEGER, true, null,
                                "Chest rows, 1 to 6", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Rows", "placement", 10, false, "number", "", "3")
                                        .withRange(1.0, (double) MAX_ROWS, "rows")),
                        layers),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty(); // unknown or diamond template — quarantine upstream
        }
        String title = Templates.text(merged, "title");
        Integer rows = wholeNumber(merged.get("rows"));
        if (title == null || rows == null || rows < 1 || rows > MAX_ROWS) {
            return Optional.empty(); // a GUI needs a title and a valid chest size
        }
        Object authored = merged.get("layers");
        if (authored != null && !(authored instanceof List<?>)) {
            return Optional.empty();
        }
        List<?> entries = authored == null ? List.of() : (List<?>) authored;
        var layers = new ArrayList<GuiLayer>();
        var skipped = new ArrayList<GuiDefinition.SkippedLayer>();
        var accepted = new HashSet<String>();
        for (int index = 0; index < entries.size(); index++) {
            try {
                GuiLayer layer = layer(id, rows, entries.get(index), accepted);
                accepted.add(layer.id());
                layers.add(layer);
            } catch (Rejection rejection) {
                skipped.add(new GuiDefinition.SkippedLayer(
                        index, rejection.layerId, rejection.type, rejection.getMessage()));
            }
        }
        return Optional.of(new GuiDefinition(id, title, rows, layers, skipped, merged));
    }

    /**
     * One {@link GuiDefinition#skipped()} layer per {@code MTR-0052}, targeting
     * {@code layers.<index>} so the editor opens the layer that is broken.
     */
    @Override
    public List<Diagnostic> diagnose(Object parsedDefinition) {
        if (!(parsedDefinition instanceof GuiDefinition gui) || gui.skipped().isEmpty()) {
            return List.of();
        }
        var out = new ArrayList<Diagnostic>();
        for (GuiDefinition.SkippedLayer skip : gui.skipped()) {
            String label = skip.layerId() != null ? "'" + skip.layerId() + "'" : "#" + skip.index();
            out.add(new Diagnostic(
                    DiagnosticCodes.GUI_LAYER_SKIPPED, Severity.WARN,
                    "GUI layer " + label + " skipped: " + skip.reason(),
                    gui.id(), null,
                    "fix the layer's fields; the other layers of this GUI still compile",
                    DiagnosticTarget.field("layers", String.valueOf(skip.index()))));
        }
        return List.copyOf(out);
    }

    /**
     * Compiles one GUI into pack output (gui.md §4) through {@link GuiCompiler}: the
     * background PNG and its glyph provider, the offset fonts actually used, and the
     * diagnostics of skipped layers. The background codepoint is the GUI's durable
     * {@link GuiBackgrounds#ALLOCATION_KIND} allocation; a used-up block quarantines this
     * GUI with {@code MTR-0053} and the pack still compiles.
     */
    @Override
    public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        if (!(definition instanceof GuiDefinition gui)) {
            return;
        }
        int number = allocations.range(gui.id(), GuiBackgrounds.ALLOCATION_KIND, 1).start();
        if (!GuiBackgrounds.fits(number)) {
            throw new CompileRejectException(
                    DiagnosticCodes.GUI_BACKGROUND_EXHAUSTED,
                    "GUI " + gui.id() + " has no background codepoint left (U+E200..U+F8FF is used up)",
                    "retire unused GUIs; the allocation table never reuses codepoints");
        }
        GuiCompilation compiled = GuiCompiler.compile(
                gui, GuiBackgrounds.codepoint(number), emitter.textures());
        compiled.diagnostics().forEach(emitter::diagnostic);
        compiled.assets().forEach(emitter::generatedAsset);
        // GUI fonts map plain ASCII (smallcaps, the vanilla grid) and reach the screen only
        // through the title's styled font keys, so they never join minecraft:default (#198)
        compiled.fonts().forEach(font -> emitter.fontProvider(
                font.fontKey(), font.provider(), PackEmitter.FontMembership.STYLED_ONLY));
    }

    // ------------------------------------------------------------------

    /** Parses one authored layer or rejects it with the reason shown to the author. */
    private GuiLayer layer(NamespacedId guiId, int rows, Object entry, Set<String> accepted) {
        if (!(entry instanceof Map<?, ?> mapping)) {
            throw new Rejection(null, null, "a layer must be a mapping of its fields");
        }
        Map<String, Object> fields = LosslessYaml.stringKeyed(mapping);
        String layerId = fields.get("id") instanceof String text ? text : null;
        String type = fields.get("type") instanceof String text && !text.isBlank() ? text : null;
        if (layerId == null || !LAYER_ID.matcher(layerId).matches()) {
            throw new Rejection(layerId, type, "id must match [a-z0-9_]+");
        }
        if (accepted.contains(layerId)) {
            throw new Rejection(layerId, type, "duplicate layer id; the first layer with this id is kept");
        }
        if (type == null) {
            throw new Rejection(layerId, null, "type is required and names a component");
        }
        NamespacedId componentId;
        try {
            componentId = type.contains(":")
                    ? NamespacedId.parse(type)
                    : NamespacedId.of(BUILTIN_NAMESPACE, type);
        } catch (IllegalArgumentException malformed) {
            throw new Rejection(layerId, type, "type '" + type + "' is not a valid component name");
        }
        GuiComponent component = components.get(componentId)
                .map(RegistryEntry::entry)
                .orElseThrow(() -> new Rejection(layerId, type, "unknown component '" + type + "'"));

        String name = fields.get("name") instanceof String text && !text.isBlank() ? text : null;
        boolean visible = flag(fields, "visible", true, layerId, type);
        boolean locked = flag(fields, "locked", false, layerId, type);
        int opacity = opacity(fields, layerId, type);
        RenderTarget target = targetOf(component, layerId, type);
        if (fields.containsKey("opacity") && target != RenderTarget.RASTER) {
            throw new Rejection(layerId, type, "opacity applies to raster layers only");
        }

        var own = new LinkedHashMap<String, Object>();
        fields.forEach((key, value) -> {
            if (!COMMON_FIELDS.contains(key)) {
                own.put(key, value);
            }
        });
        CompiledGuiLayer compiled;
        try {
            compiled = component.parse(new GuiComponentConfiguration(guiId, layerId, rows, own));
        } catch (IllegalArgumentException rejected) {
            throw new Rejection(layerId, type, rejected.getMessage());
        } catch (RuntimeException failure) {
            // containment boundary: an addon's parser bug skips its layer, never the GUI
            throw new Rejection(layerId, type, "component failed: " + failure);
        }
        if (compiled == null || compiled.target() != target) {
            throw new Rejection(layerId, type, "component produced a layer for a different target");
        }
        return new GuiLayer(layerId, componentId, name, visible, locked, opacity, compiled);
    }

    /** A component's target, read inside the layer's containment: a broken addon skips only its layer. */
    private static RenderTarget targetOf(GuiComponent component, String layerId, String type) {
        try {
            RenderTarget target = component.target();
            if (target == null) {
                throw new IllegalStateException("no render target");
            }
            return target;
        } catch (RuntimeException failure) {
            throw new Rejection(layerId, type, "component failed: " + failure);
        }
    }

    private static boolean flag(Map<String, Object> fields, String key, boolean fallback,
            String layerId, String type) {
        if (!fields.containsKey(key)) {
            return fallback;
        }
        if (fields.get(key) instanceof Boolean value) {
            return value;
        }
        throw new Rejection(layerId, type, key + " must be true or false");
    }

    private static int opacity(Map<String, Object> fields, String layerId, String type) {
        if (!fields.containsKey("opacity")) {
            return MAX_OPACITY;
        }
        Integer value = wholeNumber(fields.get("opacity"));
        if (value == null || value < 0 || value > MAX_OPACITY) {
            throw new Rejection(layerId, type, "opacity must be a whole number from 0 to " + MAX_OPACITY);
        }
        return value;
    }

    /** A whole number as authored (YAML may read it as any numeric type), or null. */
    private static Integer wholeNumber(Object value) {
        if (value instanceof Number number) {
            double decimal = number.doubleValue();
            if (Double.isFinite(decimal) && decimal == Math.rint(decimal)
                    && decimal >= Integer.MIN_VALUE && decimal <= Integer.MAX_VALUE) {
                return (int) decimal;
            }
        }
        return null;
    }

    /** A layer rejected while parsing; caught per layer, never per GUI. */
    private static final class Rejection extends RuntimeException {

        private final String layerId;
        private final String type;

        Rejection(String layerId, String type, String reason) {
            super(reason);
            this.layerId = layerId;
            this.type = type;
        }
    }
}
