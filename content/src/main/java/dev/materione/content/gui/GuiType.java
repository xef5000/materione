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
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.Templates;
import dev.materione.content.gui.logic.GuiRuleChecker;
import dev.materione.content.gui.logic.GuiRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

    private final GuiLayerParser layerParser;
    private final GuiRuleChecker ruleChecker;

    /**
     * @param components the {@code gui-components} registry layers resolve against
     */
    public GuiType(Registry<GuiComponent> components) {
        this(components, GuiRuleChecker.NONE);
    }

    /**
     * @param components the {@code gui-components} registry layers resolve against
     * @param ruleChecker checks the GUI's {@code behavior:} and {@code flows:} with the flow engine
     *        in the diagnose stage (logic.md §10); {@link GuiRuleChecker#NONE} checks nothing
     */
    public GuiType(Registry<GuiComponent> components, GuiRuleChecker ruleChecker) {
        this.layerParser = new GuiLayerParser(Objects.requireNonNull(components, "components"));
        this.ruleChecker = Objects.requireNonNull(ruleChecker, "ruleChecker");
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
                                .withRange(0.0, (double) MAX_OPACITY, "%")),
                new ParameterSchema("visible_if", ParameterType.RAW, false, null,
                        "A condition (an expression, a predicate or a composite, as in a rule's require) that "
                                + "decides whether the layer is shown, checked on every render. Item layers only "
                                + "in this build: the layer order is its priority, and the topmost visible layer "
                                + "of a slot wins", null, null)
                        .withEditor(new ParameterSchema.EditorMetadata(
                                "Visible if", "identity", 35, true, "text", "", "")));
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
                        new ParameterSchema("player_inventory", ParameterType.ENUM, false, "free",
                                "The viewer's own inventory while the GUI is open: free (clicks work) or locked "
                                        + "(every click is cancelled and gui.click_inventory fires)",
                                List.of("free", "locked"), null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Player inventory", "behavior", 20, false, "select", "", "free")
                                        .withOptions(List.of(
                                                ParameterSchema.EditorOption.of("free", "Free",
                                                        "The viewer can move their own items"),
                                                ParameterSchema.EditorOption.of("locked",
                                                        "Locked", "Clicks in the viewer's inventory are cancelled")))),
                        new ParameterSchema("refresh", ParameterType.INTEGER, false, null,
                                "Refresh clock in ticks: the window re-evaluates its dynamic layers this often "
                                        + "(conditions, templates, animations). Off when absent; gui.refresh and "
                                        + "reloads always update it", null, null)
                                .withEditor(new ParameterSchema.EditorMetadata(
                                        "Refresh", "behavior", 30, true, "number", "", "")),
                        layers,
                        dev.materione.content.logic.LogicSchemas.behavior(),
                        dev.materione.content.logic.LogicSchemas.flows(),
                        dev.materione.content.logic.LogicSchemas.open()),
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
        Integer rows = GuiLayerParser.wholeNumber(merged.get("rows"));
        if (title == null || rows == null || rows < 1 || rows > MAX_ROWS) {
            return Optional.empty(); // a GUI needs a title and a valid chest size
        }
        Object authored = merged.get("layers");
        if (authored != null && !(authored instanceof List<?>)) {
            return Optional.empty();
        }
        List<?> entries = authored == null ? List.of() : (List<?>) authored;
        var parsedLayers = layerParser.parse(id, rows, entries);
        Object authoredRefresh = merged.get("refresh");
        Integer clock = authoredRefresh == null ? null : GuiLayerParser.wholeNumber(authoredRefresh);
        boolean clockOk = authoredRefresh == null || (clock != null && clock >= 1);
        return Optional.of(new GuiDefinition(id, title, rows, parsedLayers.layers(), parsedLayers.skipped(),
                GuiRules.parse(merged), merged,
                clockOk && clock != null ? clock : 0,
                clockOk ? null : "refresh is a whole number of ticks, at least 1, not " + authoredRefresh));
    }

    /**
     * One {@link GuiDefinition#skipped()} layer per {@code MTR-0052}, targeting
     * {@code layers.<index>} so the editor opens the layer that is broken.
     */
    @Override
    public List<Diagnostic> diagnose(Object parsedDefinition) {
        if (!(parsedDefinition instanceof GuiDefinition gui)) {
            return List.of();
        }
        var out = new ArrayList<Diagnostic>();
        diagnoseLayers(gui, out);
        diagnoseInputSettings(gui, out);
        diagnoseRefresh(gui, out);
        diagnoseRules(gui, out);
        return List.copyOf(out);
    }

    /** {@code MTR-0071} when {@code refresh:} was not a clock: the GUI still loads, without one. */
    private static void diagnoseRefresh(GuiDefinition gui, List<Diagnostic> out) {
        if (gui.refreshProblem() != null) {
            out.add(new Diagnostic(
                    DiagnosticCodes.GUI_REFRESH_INVALID, Severity.WARN,
                    "GUI " + gui.id() + ": " + gui.refreshProblem() + "; the clock is off",
                    gui.id(), null,
                    "write refresh as a whole number of ticks, such as refresh: 20",
                    DiagnosticTarget.field("refresh")));
        }
    }

    /**
     * The input-slot settings that cannot apply (logic.md §25, MTR-0076): {@code accept:} on a layer that
     * is not an input, and a {@code player_inventory:} that is neither value. Each is ignored, so the GUI
     * loads and says so.
     */
    private static void diagnoseInputSettings(GuiDefinition gui, List<Diagnostic> out) {
        if (!PlayerInventory.valid(gui.raw().get("player_inventory"))) {
            out.add(new Diagnostic(
                    DiagnosticCodes.GUI_INPUT_SETTING_IGNORED, Severity.WARN,
                    gui.id() + ": player_inventory must be locked or free; free is used",
                    gui.id(), null, "write player_inventory: locked or player_inventory: free",
                    DiagnosticTarget.field("player_inventory")));
        }
        for (int index = 0; index < gui.layers().size(); index++) {
            GuiLayer layer = gui.layers().get(index);
            if (layer.compiled() instanceof SlotsComponent.Layer wells && !wells.input() && wells.accept() != null) {
                out.add(new Diagnostic(
                        DiagnosticCodes.GUI_INPUT_SETTING_IGNORED, Severity.WARN,
                        gui.id() + ": layer '" + layer.id() + "' has accept, which applies to input slots only; it is ignored",
                        gui.id(), null, "add input: true to the layer, or remove accept",
                        DiagnosticTarget.field("layers", String.valueOf(index))));
            }
        }
    }

    /**
     * The shape problems of {@code behavior:} and {@code flows:} (a section that is not a list or a
     * mapping), then whatever the rule checker finds in the rules themselves, each targeted at the
     * step path it is about ({@code behavior.2.do.0}). A rule that does not load is skipped, never
     * the GUI.
     */
    private void diagnoseRules(GuiDefinition gui, List<Diagnostic> out) {
        for (GuiRules.Problem problem : gui.rules().problems()) {
            out.add(new Diagnostic(
                    DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                    gui.id() + ": " + problem.message(),
                    gui.id(), null,
                    "the section is ignored; the GUI and its layers still work",
                    DiagnosticTarget.field(problem.field())));
        }
        // the checker also compiles the input slots' accept conditions (MTR-0074), so it runs for a GUI with
        // an accept even when it has no rules; a GUI with neither is not checked at all
        if (!gui.rules().isEmpty() || hasAccept(gui)) {
            out.addAll(ruleChecker.check(gui));
        }
        out.addAll(ruleChecker.checkLayers(gui));
    }

    private static boolean hasAccept(GuiDefinition gui) {
        return gui.layers().stream().anyMatch(layer -> layer.compiled() instanceof SlotsComponent.Layer wells
                && wells.input() && wells.accept() != null);
    }

    private static void diagnoseLayers(GuiDefinition gui, List<Diagnostic> out) {
        for (GuiDefinition.SkippedLayer skip : gui.skipped()) {
            String label = skip.layerId() != null ? "'" + skip.layerId() + "'" : "#" + skip.index();
            out.add(new Diagnostic(
                    DiagnosticCodes.GUI_LAYER_SKIPPED, Severity.WARN,
                    "GUI layer " + label + " skipped: " + skip.reason(),
                    gui.id(), null,
                    "fix the layer's fields; the other layers of this GUI still compile",
                    DiagnosticTarget.field("layers", String.valueOf(skip.index()))));
        }
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

}
