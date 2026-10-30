package dev.materione.content.gui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.CreateDocumentRequest;
import dev.materione.api.authoring.AuthoringApi.DocumentView;
import dev.materione.api.authoring.AuthoringApi.PutDocumentRequest;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.gui.GuiAuthoringApi.AddLayer;
import dev.materione.api.gui.GuiAuthoringApi.CustomizeItemRequest;
import dev.materione.api.gui.GuiAuthoringApi.CustomizeItemResult;
import dev.materione.api.gui.GuiAuthoringApi.GuiDocumentView;
import dev.materione.api.gui.GuiAuthoringApi.GuiEditRequest;
import dev.materione.api.gui.GuiAuthoringApi.GuiRender;
import dev.materione.api.gui.GuiAuthoringApi.GuiRenderRequest;
import dev.materione.api.gui.GuiAuthoringApi.GuiValidateRequest;
import dev.materione.api.gui.GuiAuthoringApi.Layer;
import dev.materione.api.gui.GuiAuthoringApi.LayerRender;
import dev.materione.api.gui.GuiAuthoringApi.PatchLayer;
import dev.materione.api.gui.GuiAuthoringApi.RemoveLayers;
import dev.materione.api.gui.GuiAuthoringApi.Saved;
import dev.materione.api.gui.GuiAuthoringApi.SetScreen;
import dev.materione.api.gui.GuiAuthoringApi.Typed;
import dev.materione.api.gui.GuiAuthoringApi.Yaml;
import dev.materione.api.gui.GuiComponent;
import dev.materione.api.gui.RenderTarget;
import dev.materione.api.registry.Registry;
import dev.materione.content.gui.raster.PngEncoder;
import dev.materione.core.authoring.AuthoringApiImpl;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.texture.PngDecoder;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The GUI surface end to end over the real document store (gui.md §7, §10): reads, edits, the
 * customized-item transaction, rendering and validation, against files on disk.
 */
class GuiAuthoringApiTest {

    private static final String PACK = "shop";
    private static final String GUI = "guis/main.yml";
    private static final ActorContext ACTOR = ActorContext.console();
    private static final String SHOP = """
            main:
              title: Shop
              rows: 3
              layers:
                - id: frame
                  type: panel
                  x: 0
                  y: 0
                  width: 176
                  height: 132
                - id: wells
                  type: slots
                  region: container
                - id: sword
                  type: item
                  slot: 13
                  item: shop:sword
                  amount: 1
            """;

    @TempDir
    Path temp;

    private DocumentStore store;
    private AuthoringApiImpl authoring;
    private GuiAuthoringApi gui;

    @BeforeEach
    void setUp() throws IOException {
        Path packsRoot = temp.resolve("packs");
        store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        Registry<GuiComponent> components = GuiTestSupport.components();
        authoring = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null, (actor, pack, changed) -> { },
                null, List::of,
                base -> new GuiAuthoringApiImpl(base, components, packsRoot,
                        MemoryAllocations::new, List.of("DIAMOND_SWORD", "NETHERITE_SWORD")));
        gui = authoring.gui();
        assertThat(authoring.createPack(ACTOR, PACK).ok()).isTrue();
        ApiResult<DocumentView> created = authoring.createDocument(ACTOR,
                new CreateDocumentRequest(PACK, GUI, "main", "Main", SHOP));
        assertThat(created.ok()).as(String.valueOf(created.diagnostics())).isTrue();
        assertThat(authoring.createDocument(ACTOR, new CreateDocumentRequest(
                PACK, "items/sword.yml", "sword", "Sword", null)).ok()).isTrue();
    }

    private int revisionOfGui() {
        return authoring.getDocument(PACK, GUI).value().revision();
    }

    private String rawGui() {
        return authoring.getDocument(PACK, GUI).value().raw();
    }

    /** The {@code item:} of the named layer, read from the parsed file. */
    @SuppressWarnings("unchecked")
    private String layerItem(String id) {
        var parsed = authoring.getDocument(PACK, GUI).value().parsed();
        var main = (Map<String, Object>) parsed.get("main");
        for (Object entry : (List<Object>) main.get("layers")) {
            var layer = (Map<String, Object>) entry;
            if (id.equals(layer.get("id"))) {
                return (String) layer.get("item");
            }
        }
        throw new AssertionError("no layer " + id);
    }

    /** Replaces the GUI file's content at its current revision (the test's own authoring, through the same API). */
    private void authorGui(String yaml) {
        assertThat(authoring.putDocument(ACTOR, new PutDocumentRequest(PACK, GUI, revisionOfGui(), yaml)).ok())
                .isTrue();
    }

    private static String message(ApiResult<?> result) {
        return result.ok() ? "" : result.diagnostics().get(0).message();
    }

    // ------------------------------------------------------------------
    // reads

    @Test
    void getReturnsTheTypedViewOfTheFile() {
        ApiResult<GuiDocumentView> result = gui.get(PACK, GUI);
        assertThat(result.ok()).isTrue();
        GuiDocumentView view = result.value();
        assertThat(view.id()).isEqualTo("main");
        assertThat(view.title()).isEqualTo("Shop");
        assertThat(view.rows()).isEqualTo(3);
        assertThat(view.revision()).isEqualTo(1);
        assertThat(view.layers()).extracting(Layer::id).containsExactly("frame", "wells", "sword");
        Layer sword = view.layers().get(2);
        assertThat(sword.type()).isEqualTo("item");
        assertThat(sword.visible()).isTrue();
        assertThat(sword.parameters()).containsEntry("slot", 13).containsEntry("item", "shop:sword");
    }

    @Test
    void getRefusesAFileThatIsNotAGui() {
        ApiResult<GuiDocumentView> result = gui.get(PACK, "items/sword.yml");
        assertThat(result.ok()).isFalse();
        assertThat(message(result)).contains("not a GUI document");
    }

    @Test
    void componentsDescribeTheRegistryWithTargets() {
        var components = gui.components();
        assertThat(components).extracting(component -> component.id().toString())
                .containsExactly("materione:panel", "materione:slots", "materione:image", "materione:paint",
                        "materione:button", "materione:text", "materione:item");
        assertThat(components.get(0).target()).isEqualTo(RenderTarget.RASTER);
        assertThat(components.get(6).target()).isEqualTo(RenderTarget.SLOT);
    }

    // ------------------------------------------------------------------
    // edits

    @Test
    void editsApplyInOrderInOneRevisionedWrite() {
        int before = revisionOfGui();
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, before, List.of(
                new AddLayer(0, "panel", "backdrop", Map.of("x", 4, "y", 4, "width", 8, "height", 8)),
                new PatchLayer("sword", Map.of("amount", 3), List.of()),
                new RemoveLayers(List.of("wells")),
                new SetScreen("Market", 2)), List.of()));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        assertThat(result.value().revision()).isEqualTo(before + 1);
        assertThat(result.value().layers()).extracting(Layer::id).containsExactly("backdrop", "frame", "sword");
        assertThat(result.value().title()).isEqualTo("Market");
        assertThat(result.value().rows()).isEqualTo(2);
        // the file is the source of truth: a fresh read agrees
        assertThat(gui.get(PACK, GUI).value().layers()).extracting(Layer::id)
                .containsExactly("backdrop", "frame", "sword");
    }

    @Test
    void aStaleRevisionIsAConflictAndWritesNothing() {
        String before = rawGui();
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, 99, List.of(
                new RemoveLayers(List.of("frame"))), List.of()));
        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT);
        assertThat(rawGui()).isEqualTo(before);
    }

    @Test
    void aRefusedEditRollsBackTheWholeBatch() {
        String before = rawGui();
        int revision = revisionOfGui();
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, revision, List.of(
                new AddLayer(0, "panel", "good", Map.of()),
                new AddLayer(0, "hologram", "bad", Map.of())), List.of()));
        assertThat(result.ok()).isFalse();
        assertThat(message(result)).contains("unknown component 'hologram'");
        assertThat(rawGui()).isEqualTo(before);
        assertThat(revisionOfGui()).isEqualTo(revision);
    }

    @Test
    void anEditThatChangesNothingWritesNothing() {
        String before = rawGui();
        int revision = revisionOfGui();
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, revision, List.of(
                new SetScreen(null, null)), List.of()));
        assertThat(result.ok()).isTrue();
        assertThat(rawGui()).isEqualTo(before);
        assertThat(revisionOfGui()).isEqualTo(revision);
    }

    @Test
    void aRefusedAssetKeepsTheEditsOutToo() {
        String before = rawGui();
        int revision = revisionOfGui();
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, revision, List.of(
                new RemoveLayers(List.of("frame"))),
                List.of(new AuthoringApi.ModelAsset("../escape.png", new byte[] {1, 2, 3}))));
        assertThat(result.ok()).isFalse();
        assertThat(rawGui()).isEqualTo(before);
        assertThat(revisionOfGui()).isEqualTo(revision);
    }

    @Test
    void anEditWarnsAboutLayersTheCompilerWillSkip() {
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, revisionOfGui(), List.of(
                new AddLayer(0, "text", "heading", Map.of("text", "a<b", "x", 8, "y", 6))), List.of()));
        assertThat(result.ok()).isTrue();
        assertThat(result.diagnostics()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo(DiagnosticCodes.GUI_LAYER_SKIPPED);
            assertThat(warning.severity()).isEqualTo(Severity.WARN);
        });
    }

    @Test
    void anEditRefusesAGuiWhoseTwoLayersShareAnId() {
        authorGui("""
                main:
                  title: Shop
                  rows: 1
                  layers:
                    - id: frame
                      type: panel
                    - id: frame
                      type: panel
                """);
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, revisionOfGui(), List.of(
                new SetScreen("Next", null)), List.of()));
        assertThat(result.ok()).isFalse();
        assertThat(message(result)).contains("fix the file before editing");
    }

    @Test
    void unknownFieldsSurviveAnEditToo() {
        authorGui("""
                main:
                  title: Shop
                  rows: 1
                  note: keep me
                  layers:
                    - id: frame
                      type: panel
                      designer_note: also kept
                """);
        ApiResult<GuiDocumentView> result = gui.edit(ACTOR, new GuiEditRequest(PACK, GUI, revisionOfGui(), List.of(
                new AddLayer(0, "panel", "back", Map.of())), List.of()));
        assertThat(result.ok()).isTrue();
        assertThat(rawGui()).contains("note: keep me").contains("designer_note: also kept");
    }

    // ------------------------------------------------------------------
    // customize

    @Test
    void aMaterioneItemIsCopiedIntoTheGuiFolderAndTheLayerRepointed() {
        // the pack already has an item called "sword", so the layer's own id is taken: the GUI's stem prefixes it
        int revision = revisionOfGui();
        ApiResult<CustomizeItemResult> result = gui.customizeItem(ACTOR,
                new CustomizeItemRequest(PACK, GUI, revision, "sword"));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        CustomizeItemResult created = result.value();
        assertThat(created.itemId()).isEqualTo("main_sword");
        assertThat(created.itemPath()).isEqualTo("items/gui/main/main_sword.yml");
        assertThat(created.reference()).isEqualTo("shop:main_sword");
        assertThat(created.revision()).isEqualTo(revision + 1);
        assertThat(authoring.getDocument(PACK, "items/gui/main/main_sword.yml").ok()).isTrue();
        // the writer quotes a value with a colon; the parsed layer is the test's authority
        assertThat(layerItem("sword")).isEqualTo("shop:main_sword");
    }

    @Test
    void aFreeLayerIdIsTheItemIdWhenNothingClashes() {
        authorGui("""
                main:
                  title: Shop
                  rows: 1
                  layers:
                    - id: blade
                      type: item
                      slot: 0
                      item: shop:sword
                """);
        ApiResult<CustomizeItemResult> result = gui.customizeItem(ACTOR,
                new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "blade"));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        assertThat(result.value().itemPath()).isEqualTo("items/gui/main/blade.yml");
        assertThat(layerItem("blade")).isEqualTo("shop:blade");
    }

    @Test
    void aVanillaBaseBecomesAMaterialItem() {
        authorGui("""
                main:
                  title: Shop
                  rows: 1
                  layers:
                    - id: blade
                      type: item
                      slot: 0
                      item: minecraft:diamond_sword
                """);
        ApiResult<CustomizeItemResult> result = gui.customizeItem(ACTOR,
                new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "blade"));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        DocumentView created = authoring.getDocument(PACK, "items/gui/main/blade.yml").value();
        assertThat(created.parsed().toString()).contains("material=DIAMOND_SWORD");
        assertThat(layerItem("blade")).isEqualTo("shop:blade");
    }

    @Test
    void aLayerThatAlreadyHasItsOwnItemIsRefused() {
        int revision = revisionOfGui();
        assertThat(gui.customizeItem(ACTOR, new CustomizeItemRequest(PACK, GUI, revision, "sword")).ok()).isTrue();
        String after = rawGui();
        ApiResult<CustomizeItemResult> again = gui.customizeItem(ACTOR,
                new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "sword"));
        assertThat(again.ok()).isFalse();
        assertThat(message(again)).contains("already has its own item");
        assertThat(rawGui()).isEqualTo(after);
    }

    @Test
    void aStaleRevisionCreatesNoItem() {
        ApiResult<CustomizeItemResult> result = gui.customizeItem(ACTOR,
                new CustomizeItemRequest(PACK, GUI, 42, "sword"));
        assertThat(result.ok()).isFalse();
        assertThat(authoring.getDocument(PACK, "items/gui/main/main_sword.yml").ok()).isFalse();
    }

    @Test
    void customizeRefusesWhatTheStudioRefused() {
        authorGui("""
                main:
                  title: Shop
                  rows: 1
                  layers:
                    - id: blade
                      type: item
                      slot: 0
                      item: minecraft:bedrock
                    - id: blank
                      type: item
                      slot: 1
                      item: ""
                    - id: frame
                      type: panel
                """);
        assertThat(message(gui.customizeItem(ACTOR, new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "blade"))))
                .contains("not a material this server knows");
        assertThat(message(gui.customizeItem(ACTOR, new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "blank"))))
                .contains("Choose an item first");
        assertThat(message(gui.customizeItem(ACTOR, new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "frame"))))
                .contains("is not an item layer");
        assertThat(message(gui.customizeItem(ACTOR, new CustomizeItemRequest(PACK, GUI, revisionOfGui(), "nope"))))
                .contains("no layer 'nope'");
    }

    // ------------------------------------------------------------------
    // render and validate

    @Test
    void renderOfASavedGuiShipsTheCompilersBackgroundAndTheCompiledOrder() {
        ApiResult<GuiRender> result = gui.render(new GuiRenderRequest(new Saved(PACK, GUI), List.of()));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        GuiRender render = result.value();
        assertThat(render.width()).isEqualTo(176);
        assertThat(render.height()).isEqualTo(114 + 18 * 3);
        var pixels = PngDecoder.decode(render.background()).orElseThrow();
        assertThat(pixels.width()).isEqualTo(176);
        assertThat(pixels.height()).isEqualTo(168);
        assertThat(render.layers()).extracting(LayerRender::id).containsExactly("frame", "wells", "sword");
        assertThat(render.layers()).extracting(LayerRender::compiledRank).containsExactly(0, 1, 2);
        assertThat(render.layers()).allSatisfy(layer -> assertThat(layer.skipped()).isFalse());
    }

    @Test
    void renderOfADraftReportsSkippedLayersAndRanksTheRest() {
        var draft = new Typed(new GuiDocumentView(PACK, null, "main", "Shop", 1, 0, List.of(
                new Layer("heading", "text", null, true, false, null,
                        Map.of("text", "Shop <b>", "x", 8, "y", 6)),
                new Layer("ghost", "hologram", null, true, false, null, Map.of()),
                new Layer("frame", "panel", null, true, false, null, Map.of("width", 176, "height", 132)))));
        ApiResult<GuiRender> result = gui.render(new GuiRenderRequest(draft, List.of()));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        GuiRender render = result.value();
        // a parse skip keeps its place in the list, with no target
        LayerRender ghost = render.layers().get(1);
        assertThat(ghost.id()).isEqualTo("ghost");
        assertThat(ghost.target()).isNull();
        assertThat(ghost.skipped()).isTrue();
        assertThat(ghost.reason()).contains("unknown component 'hologram'");
        // a compile skip: the text with a reserved character
        LayerRender heading = render.layers().get(0);
        assertThat(heading.skipped()).isTrue();
        assertThat(heading.reason()).contains("may not contain");
        // the panel is the only placed raster layer, so it ranks first
        assertThat(render.layers().get(2).compiledRank()).isEqualTo(0);
        assertThat(render.diagnostics()).extracting(Diagnostic::code)
                .containsOnly(DiagnosticCodes.GUI_LAYER_SKIPPED);
    }

    @Test
    void validateOfADraftIsTheCompilesDiagnostics() {
        var draft = new Typed(new GuiDocumentView(PACK, null, "main", "Shop", 1, 0, List.of(
                new Layer("heading", "text", null, true, false, null,
                        Map.of("text", "Shop <b>", "x", 8, "y", 6)))));
        ApiResult<List<Diagnostic>> validated = gui.validate(new GuiValidateRequest(draft, List.of()));
        assertThat(validated.ok()).isTrue();
        ApiResult<GuiRender> rendered = gui.render(new GuiRenderRequest(draft, List.of()));
        assertThat(validated.value()).isEqualTo(rendered.value().diagnostics());
        assertThat(validated.value()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.target().field()).containsExactly("layers", "0"));
    }

    @Test
    void validateRunsTheRuleCheckerOnYamlAndSavedGuisAndTheFindingsKeepTheirStepPath() {
        Path packsRoot = temp.resolve("packs");
        var seen = new java.util.ArrayList<String>();
        GuiAuthoringApi checked = new AuthoringApiImpl(store, new AuditLog(temp.resolve("audit2.log")), packsRoot,
                new ModelTransactionService(store, packsRoot, new AuditLog(temp.resolve("audit3.log"))), null,
                () -> null, (actor, pack, changed) -> { }, null, List::of,
                base -> new GuiAuthoringApiImpl(base, GuiTestSupport.components(), packsRoot,
                        MemoryAllocations::new, List.of(), definition -> {
                            seen.add(definition.id() + " rules=" + definition.rules().behavior().size());
                            return List.of(new Diagnostic(DiagnosticCodes.BEHAVIOR_RULE_INVALID, Severity.WARN,
                                    "rule 'r' broke", definition.id(), null, "fix it",
                                    dev.materione.api.diagnostics.DiagnosticTarget.field("behavior", "0", "do", "1")));
                        })).gui();
        String withRules = SHOP + "  behavior:\n    - on: gui.click\n      do:\n        - gui.close\n";

        ApiResult<List<Diagnostic>> fromYaml = checked.validate(new GuiValidateRequest(
                new Yaml(PACK, withRules), List.of()));
        ApiResult<List<Diagnostic>> fromTheFile = checked.validate(new GuiValidateRequest(
                new Saved(PACK, GUI), List.of()));

        assertThat(fromYaml.value()).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.target().field()).containsExactly("behavior", "0", "do", "1"));
        assertThat(seen).containsExactly("shop:main rules=1");
        assertThat(fromTheFile.value()).as("the saved file has no rules, so nothing is checked").isEmpty();
    }

    @Test
    void validateOfUnparseableYamlIsAParseFailure() {
        ApiResult<List<Diagnostic>> result = gui.validate(new GuiValidateRequest(
                new Yaml(PACK, "main: [unclosed"), List.of()));
        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.CONTENT_PARSE_FAILED);
    }

    @Test
    void validateOfAGuiWithoutATitleReportsItsParseFailure() {
        var draft = new Typed(new GuiDocumentView(PACK, null, "main", null, 1, 0, List.of()));
        ApiResult<List<Diagnostic>> result = gui.validate(new GuiValidateRequest(draft, List.of()));
        assertThat(result.ok()).isTrue();
        assertThat(result.value()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.CONTENT_SCHEMA_INVALID);
            assertThat(diagnostic.message()).contains("failed to parse as gui");
        });
    }

    @Test
    void aDraftPngOverlaysTheSavedTexture() {
        var draft = new Typed(new GuiDocumentView(PACK, null, "main", "Shop", 1, 0, List.of(
                new Layer("art", "paint", null, true, false, null, Map.of("image", "gui/main/art")))));
        ApiResult<GuiRender> result = gui.render(new GuiRenderRequest(draft,
                List.of(new AuthoringApi.ModelAsset("assets/textures/gui/main/art.png", pngOf(2, 2)))));
        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        assertThat(result.value().layers().get(0).skipped()).isFalse();
    }

    @Test
    void aDraftPaintLayerWithoutItsTextureIsSkippedAndSaysWhy() {
        var draft = new Typed(new GuiDocumentView(PACK, null, "main", "Shop", 1, 0, List.of(
                new Layer("art", "paint", null, true, false, null, Map.of("image", "gui/main/missing")))));
        ApiResult<GuiRender> result = gui.render(new GuiRenderRequest(draft, List.of()));
        assertThat(result.ok()).isTrue();
        assertThat(result.value().layers().get(0).skipped()).isTrue();
        assertThat(result.value().layers().get(0).reason()).isNotBlank();
    }

    @Test
    void aDraftAssetThatIsNotAPngIsRefused() {
        var draft = new Typed(new GuiDocumentView(PACK, null, "main", "Shop", 1, 0, List.of()));
        ApiResult<GuiRender> result = gui.render(new GuiRenderRequest(draft,
                List.of(new AuthoringApi.ModelAsset("assets/textures/gui/x.png", new byte[] {1, 2, 3}))));
        assertThat(result.ok()).isFalse();
        assertThat(message(result)).contains("not a readable PNG");
    }

    @Test
    void renderAndValidateNeverWriteOrBumpARevision() {
        String before = rawGui();
        int revision = revisionOfGui();
        gui.render(new GuiRenderRequest(new Saved(PACK, GUI), List.of()));
        gui.validate(new GuiValidateRequest(new Yaml(PACK, SHOP.replace("Shop", "Market")), List.of()));
        assertThat(rawGui()).isEqualTo(before);
        assertThat(revisionOfGui()).isEqualTo(revision);
    }

    @Test
    void aYamlDraftNeedsExactlyOneGui() {
        assertThat(gui.validate(new GuiValidateRequest(
                new Yaml(PACK, "other:\n  title: x\n  rows: 1\n  layers: []\n"), List.of())).ok()).isTrue();
        ApiResult<List<Diagnostic>> two = gui.validate(new GuiValidateRequest(
                new Yaml(PACK, "a:\n  title: x\n  rows: 1\nb:\n  title: y\n  rows: 1\n"), List.of()));
        assertThat(two.ok()).isFalse();
        assertThat(message(two)).contains("holds 2 GUI resources");
    }

    /** A PNG of solid red pixels, for a draft texture. */
    static byte[] pngOf(int width, int height) {
        int[] argb = new int[width * height];
        Arrays.fill(argb, 0xFFFF0000);
        return PngEncoder.encodeRgba(width, height, argb);
    }
}
