package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.CompiledGuiLayer;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.GuiComponentConfiguration;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.gui.TitleSink;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.registry.RegistryService;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gui content type (gui.md §2–§4): the seven built-ins resolve through the
 * registry, one bad layer quarantines alone with a stable diagnostic, and the
 * golden files round-trip byte for byte.
 */
class GuiTypeTest {

    private static final NamespacedId GUI = NamespacedId.of("demo", "shop");

    /** The gui-components registry with the built-ins registered, as bootstrap does. */
    private static Registry<GuiComponent> components(GuiComponent... addons) {
        var registries = new RegistryService();
        var registry = registries.registry(RegistryKeys.GUI_COMPONENTS, GuiComponent.class);
        for (GuiComponent component : GuiComponents.builtins()) {
            registry.register(NamespacedId.of("materione", component.name()),
                    component, component.describe(), "materione");
        }
        for (GuiComponent addon : addons) {
            registry.register(NamespacedId.parse("example:" + addon.name()),
                    addon, addon.describe(), "example");
        }
        return registry;
    }

    /** The document under {@code key} in a golden GUI file, as the pipeline hands it to parse. */
    private static Map<String, Object> golden(String file, String key) throws IOException {
        String raw = Files.readString(GoldenFiles.fixturesRoot().resolve("gui").resolve(file));
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) LosslessYaml.parse(raw).data().get(key);
        return definition;
    }

    private static GuiDefinition parse(GuiType type, String file, String key) throws IOException {
        var parsed = type.parse(GUI, golden(file, key), Map.of());
        assertThat(parsed).as(file).isPresent();
        return (GuiDefinition) parsed.get();
    }

    @Test
    void everyFixtureRoundTripsByteForByte() throws IOException {
        try (var files = Files.list(GoldenFiles.fixturesRoot().resolve("gui"))) {
            var yml = files.filter(p -> p.toString().endsWith(".yml")).toList();
            assertThat(yml).isNotEmpty();
            for (var file : yml) {
                String raw = Files.readString(file);
                assertThat(LosslessYaml.parse(raw).save()).as("round trip of %s", file).isEqualTo(raw);
            }
        }
    }

    @Test
    void sevenBuiltInComponentsParseAndHiddenLayersAreKept() throws IOException {
        var type = new GuiType(components());
        GuiDefinition shop = parse(type, "shop.yml", "shop");

        assertThat(shop.title()).isEqualTo("Shop");
        assertThat(shop.rows()).isEqualTo(6);
        assertThat(shop.skipped()).isEmpty();
        assertThat(shop.layers()).extracting(GuiLayer::id)
                .containsExactly("frame", "wells", "art", "banner", "buy", "heading", "sword", "hidden_note");
        assertThat(shop.layers()).extracting(layer -> layer.component().toString())
                .containsExactly("materione:panel", "materione:slots", "materione:paint", "materione:image",
                        "materione:button", "materione:text", "materione:item", "materione:text");
        assertThat(shop.layers().get(7).visible()).isFalse();
        assertThat(shop.layers().get(3).opacity()).isEqualTo(80);
        assertThat(shop.layers().get(0).opacity()).isEqualTo(100);
        assertThat(shop.layers().get(0).name()).isEqualTo("Frame");

        assertThat(type.diagnose(shop)).isEmpty();
    }

    @Test
    void theTargetOfEachComponentIsItsRenderSurface() throws IOException {
        var type = new GuiType(components());
        GuiDefinition shop = parse(type, "shop.yml", "shop");
        var targets = shop.layers().stream().map(layer -> layer.compiled().target()).toList();
        assertThat(targets).containsExactly(RenderTarget.RASTER, RenderTarget.RASTER, RenderTarget.RASTER,
                RenderTarget.RASTER, RenderTarget.RASTER, RenderTarget.TEXT, RenderTarget.SLOT, RenderTarget.TEXT);
    }

    @Test
    void textRunAndItemSlotCarryTheirAuthoredValues() throws IOException {
        var type = new GuiType(components());
        GuiDefinition shop = parse(type, "shop.yml", "shop");
        var heading = (TextComponent.Layer) shop.layers().get(5).compiled();
        assertThat(heading.text()).isEqualTo("Shop");
        assertThat(heading.font()).isEqualTo(TitleSink.Font.SMALLCAPS);
        assertThat(heading.argb()).isEqualTo(0xFF404040);
        var sword = (ItemComponent.Layer) shop.layers().get(6).compiled();
        assertThat(sword.slot()).isEqualTo(13);
        assertThat(sword.item()).isEqualTo(NamespacedId.parse("shop:gui/main/sword"));
        assertThat(sword.amount()).isEqualTo(1);
    }

    @Test
    void oneBadLayerQuarantinesAloneAndTheRestOfTheGuiParses() throws IOException {
        var type = new GuiType(components());
        GuiDefinition gallery = parse(type, "bad-layer.yml", "gallery");

        assertThat(gallery.layers()).extracting(GuiLayer::id).containsExactly("frame", "wells");
        assertThat(gallery.skipped()).extracting(GuiDefinition.SkippedLayer::index)
                .containsExactly(1, 2, 3, 5, 6);
        assertThat(gallery.skipped().get(0).reason()).contains("width is required");
        assertThat(gallery.skipped().get(1).reason()).contains("unknown component 'example:ghost'");
        assertThat(gallery.skipped().get(2).reason()).contains("duplicate layer id");
        assertThat(gallery.skipped().get(3).layerId()).isEqualTo("lonely_item");
        assertThat(gallery.skipped().get(3).type()).isEqualTo("item");
        assertThat(gallery.skipped().get(3).reason()).contains("slot 40");
        assertThat(gallery.skipped().get(4).reason()).contains("opacity applies to raster layers only");
    }

    @Test
    void everySkippedLayerIsDiagnosedUnderItsStableCodeAtItsLayerIndex() throws IOException {
        var type = new GuiType(components());
        GuiDefinition gallery = parse(type, "bad-layer.yml", "gallery");

        List<Diagnostic> diagnostics = type.diagnose(gallery);
        assertThat(diagnostics).hasSize(5);
        assertThat(diagnostics).allSatisfy(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.GUI_LAYER_SKIPPED);
            assertThat(diagnostic.code().code()).isEqualTo("MTR-0052");
            assertThat(diagnostic.resource()).isEqualTo(GUI);
        });
        assertThat(diagnostics).extracting(d -> d.target().field())
                .containsExactly(List.of("layers", "1"), List.of("layers", "2"), List.of("layers", "3"),
                        List.of("layers", "5"), List.of("layers", "6"));
        assertThat(diagnostics.get(0).message()).contains("'broken'");
    }

    @Test
    void theGuiItselfIsRejectedOnlyForItsOwnFields() {
        var type = new GuiType(components());
        var layers = List.<Object>of();
        assertThat(type.parse(GUI, Map.of("rows", 3, "layers", layers), Map.of())).as("no title").isEmpty();
        assertThat(type.parse(GUI, Map.of("title", "T", "layers", layers), Map.of())).as("no rows").isEmpty();
        for (int rows : new int[] {0, 7}) {
            assertThat(type.parse(GUI, Map.of("title", "T", "rows", rows), Map.of()))
                    .as("rows %d", rows).isEmpty();
        }
        assertThat(type.parse(GUI, Map.of("title", "T", "rows", 2, "layers", "not a list"), Map.of()))
                .as("layers must be a list").isEmpty();
        assertThat(type.parse(GUI, Map.of("title", "T", "rows", 2), Map.of())).as("no layers is a blank chest")
                .isPresent();
    }

    @Test
    void layerIdsAndTypesAreCheckedBeforeAnyComponentRuns() {
        var type = new GuiType(components());
        var definition = Map.<String, Object>of("title", "T", "rows", 1, "layers", List.of(
                Map.of("id", "Bad-Id", "type", "panel", "width", 10, "height", 10),
                Map.of("id", "no_type"),
                Map.of("id", "fine", "type", "panel", "width", 10, "height", 10, "visible", "yes"),
                "not a mapping"));
        GuiDefinition gui = (GuiDefinition) type.parse(GUI, definition, Map.of()).orElseThrow();
        assertThat(gui.layers()).isEmpty();
        assertThat(gui.skipped()).extracting(GuiDefinition.SkippedLayer::reason).containsExactly(
                "id must match [a-z0-9_]+",
                "type is required and names a component",
                "visible must be true or false",
                "a layer must be a mapping of its fields");
    }

    @Test
    void addonComponentsResolveThroughTheRegistryAndAContainedFailureSkipsOnlyItsLayer() throws IOException {
        var tag = new TagComponent();
        var broken = new BrokenComponent();
        var type = new GuiType(components(tag, broken));
        var definition = Map.<String, Object>of("title", "T", "rows", 1, "layers", List.of(
                Map.of("id", "label", "type", "example:tag", "text", "hello"),
                Map.of("id", "boom", "type", "example:broken"),
                Map.of("id", "frame", "type", "panel", "width", 10, "height", 10)));

        GuiDefinition gui = (GuiDefinition) type.parse(GUI, definition, Map.of()).orElseThrow();
        assertThat(gui.layers()).extracting(GuiLayer::id).containsExactly("label", "frame");
        assertThat(gui.layers().get(0).component()).isEqualTo(NamespacedId.parse("example:tag"));
        assertThat(gui.skipped()).hasSize(1);
        assertThat(gui.skipped().get(0).reason()).startsWith("component failed:");
    }

    @Test
    void theComponentReceivesOnlyItsOwnFieldsAndTheGuiGeometry() {
        var recorder = new RecordingComponent();
        var type = new GuiType(components(recorder));
        type.parse(GUI, Map.of("title", "T", "rows", 4, "layers", List.of(
                Map.of("id", "rec", "type", "example:recording", "name", "Rec", "locked", true,
                        "own_field", 42))), Map.of());

        assertThat(recorder.seen.guiId()).isEqualTo(GUI);
        assertThat(recorder.seen.layerId()).isEqualTo("rec");
        assertThat(recorder.seen.rows()).isEqualTo(4);
        assertThat(recorder.seen.parameters()).containsExactly(Map.entry("own_field", 42));
    }

    @Test
    void describeDeclaresTheCommonLayerFieldsForTheStudio() {
        SelfDescription description = new GuiType(components()).describe();
        assertThat(description.parameters()).extracting(ParameterSchema::id)
                .containsExactly("title", "rows", "layers");
        ParameterSchema layers = description.parameters().get(2);
        assertThat(layers.nested()).extracting(ParameterSchema::id)
                .containsExactly("id", "type", "name", "visible", "locked", "opacity");
        assertThat(layers.nested().get(1).editor().referenceKind()).isEqualTo("gui-component");
        assertThat(description.parameters().get(1).editor().max()).isEqualTo(6.0);
        assertThat(new GuiType(components()).name()).isEqualTo("gui");
        assertThat(new GuiType(components()).directory()).isEqualTo("guis");
    }

    @Test
    void componentsDescribeTheirOwnFieldsWithEditorMetadata() {
        for (GuiComponent component : GuiComponents.builtins()) {
            assertThat(component.describe().displayName()).as(component.name()).isNotBlank();
            assertThat(component.describe().parameters()).as(component.name()).isNotEmpty();
            for (ParameterSchema parameter : component.describe().parameters()) {
                assertThat(parameter.editor().label()).as(component.name() + "." + parameter.id()).isNotBlank();
            }
        }
        assertThat(GuiComponents.builtins()).extracting(GuiComponent::name)
                .containsExactly("panel", "slots", "image", "paint", "button", "text", "item");
        var textTargets = GuiComponents.builtins().stream().map(GuiComponent::target).toList();
        assertThat(textTargets).containsExactly(RenderTarget.RASTER, RenderTarget.RASTER, RenderTarget.RASTER,
                RenderTarget.RASTER, RenderTarget.RASTER, RenderTarget.TEXT, RenderTarget.SLOT);
    }

    @Test
    void aBevelRejectsAMisspeltColourKeyInsteadOfRenderingTheDefault() {
        var component = new PanelComponent();
        assertThatBadStyle(component, Map.of("fil", "#FF0000"));
        assertThatBadStyle(component, Map.of("fill", "red"));
    }

    private static void assertThatBadStyle(GuiComponent component, Map<String, Object> style) {
        var configuration = new GuiComponentConfiguration(GUI, "p", 3,
                Map.of("width", 10, "height", 10, "style", style));
        assertThatThrownBy(() -> component.parse(configuration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("layer p");
    }

    // ------------------------------------------------------------------

    /** A minimal addon component: a text run whose payload is one string. */
    private static final class TagComponent implements GuiComponent {

        @Override
        public String name() {
            return "tag";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Tag", "A test addon", List.of(
                    new ParameterSchema("text", ParameterType.STRING, true, null, "text", null, null)), "1.0.0");
        }

        @Override
        public RenderTarget target() {
            return RenderTarget.TEXT;
        }

        @Override
        public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
            return new CompiledGuiLayer() {
                @Override
                public RenderTarget target() {
                    return RenderTarget.TEXT;
                }
            };
        }
    }

    /** An addon whose parser has a bug: the layer is skipped, the GUI is not. */
    private static final class BrokenComponent implements GuiComponent {

        @Override
        public String name() {
            return "broken";
        }

        @Override
        public SelfDescription describe() {
            return SelfDescription.of("Broken", "Always throws");
        }

        @Override
        public RenderTarget target() {
            return RenderTarget.RASTER;
        }

        @Override
        public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
            throw new IllegalStateException("bug in an addon parser");
        }
    }

    /** Captures the configuration the gui type hands its component. */
    private static final class RecordingComponent implements GuiComponent {

        GuiComponentConfiguration seen;

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public SelfDescription describe() {
            return SelfDescription.of("Recording", "Records its configuration");
        }

        @Override
        public RenderTarget target() {
            return RenderTarget.SLOT;
        }

        @Override
        public CompiledGuiLayer parse(GuiComponentConfiguration configuration) {
            seen = configuration;
            return new CompiledGuiLayer() {
                @Override
                public RenderTarget target() {
                    return RenderTarget.SLOT;
                }
            };
        }
    }
}
