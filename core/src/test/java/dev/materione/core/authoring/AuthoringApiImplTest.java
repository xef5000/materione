package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.content.ResourcePresentation;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.document.PackWatcher;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The authoring API against a real document store: fail-soft conflicts
 * ({@code MTR-0034}), lossless patches that leave every untouched line
 * byte-identical (master §5.6), actor-attributed history, and the scaffold
 * path — the same semantics the editor RPC surface asserts, expressed
 * through the typed API.
 */
class AuthoringApiImplTest {

    @TempDir
    Path temp;

    private final RecordingReloads reloads = new RecordingReloads();

    private AuthoringApi api() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        return new AuthoringApiImpl(
                documents,
                new AuditLog(temp.resolve("audit.log")),
                packsRoot,
                new ModelTransactionService(documents, packsRoot, new AuditLog(temp.resolve("audit.log"))),
                null,
                () -> null,
                reloads);
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    // ------------------------------------------------------------------

    @Test
    void stalePutDocumentReturnsConflictNotAnException() {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, "wand:\n  material: STICK\n"));

        ApiResult<AuthoringApi.DocumentView> stale = api.putDocument(editor(),
                new AuthoringApi.PutDocumentRequest(
                        "demo", "items/wand.yml", 0, "wand:\n  material: BLAZE_ROD\n"));

        assertThat(stale.ok()).isFalse();
        assertThat(stale.diagnostics()).hasSize(1);
        assertThat(stale.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
        assertThat(stale.diagnostics().get(0).hint())
                .isEqualTo("re-read the document and re-apply your change");
        // the losing write still audited, exactly like the editor op
        try {
            assertThat(Files.readString(temp.resolve("audit.log"))).contains("edit.put.conflict");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void syntaxRejectionsCarryMtr0007BeforeAnythingTouchesDisk() {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();

        ApiResult<AuthoringApi.DocumentView> broken = api.putDocument(editor(),
                new AuthoringApi.PutDocumentRequest("demo", "items/bad.yml", 0, "a: [broken\n"));

        assertThat(broken.ok()).isFalse();
        assertThat(broken.diagnostics().get(0).code().code()).isEqualTo("MTR-0007");
        assertThat(temp.resolve("packs/demo/items/bad.yml")).doesNotExist();
    }

    @Test
    void setScalarPatchesExactlyOneLineAndKeepsCommentsByteIdentical() throws Exception {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        String fixture = """
                # a comment worth keeping
                wand:
                  material: STICK # inline comment
                  display_name: "Old Name"
                """;
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, fixture));

        ApiResult<AuthoringApi.DocumentView> patched = api.setScalar(editor(),
                new AuthoringApi.SetScalarRequest(
                        "demo", "items/wand.yml", 1,
                        List.of("wand", "display_name"), "New Name"));

        assertThat(patched.ok()).isTrue();
        assertThat(patched.value().revision()).isEqualTo(2);
        List<String> before = fixture.lines().toList();
        List<String> after = Files.readAllLines(
                temp.resolve("packs/demo/items/wand.yml"), StandardCharsets.UTF_8);
        assertThat(after).hasSameSizeAs(before);
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < before.size(); i++) {
            if (!before.get(i).equals(after.get(i))) {
                changed.add(i);
            }
        }
        assertThat(changed).containsExactly(3);
        assertThat(after.get(3)).contains("New Name");
        assertThat(after.get(0)).isEqualTo("# a comment worth keeping");
        assertThat(after.get(1)).isEqualTo("wand:");
        assertThat(after.get(2)).isEqualTo("  material: STICK # inline comment");
    }

    @Test
    void setFieldsBatchesIntoOneRevisionAndPatchesOnlyThoseLines() throws Exception {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        String fixture = """
                # a comment worth keeping
                wand:
                  material: STICK # inline comment
                  display_name: "Old Name"
                """;
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, fixture));

        ApiResult<AuthoringApi.DocumentView> saved = api.setFields(editor(),
                new AuthoringApi.SetFieldsRequest("demo", "items/wand.yml", 1, List.of(
                        new AuthoringApi.FieldEdit(List.of("wand", "display_name"), "New Name"),
                        new AuthoringApi.FieldEdit(List.of("wand", "material"), "DIAMOND"))));

        assertThat(saved.ok()).isTrue();
        assertThat(saved.value().revision()).isEqualTo(2);
        List<String> after = Files.readAllLines(
                temp.resolve("packs/demo/items/wand.yml"), StandardCharsets.UTF_8);
        assertThat(after.get(0)).isEqualTo("# a comment worth keeping");
        assertThat(after.get(1)).isEqualTo("wand:");
        assertThat(after.get(2)).contains("DIAMOND").contains("# inline comment");
        assertThat(after.get(3)).contains("New Name");
        // one studio save = one journal entry
        ApiResult<List<AuthoringApi.RevisionInfo>> history =
                api.getHistory("demo", "items/wand.yml", 10);
        assertThat(history.value()).hasSize(2);
        assertThat(Files.readString(temp.resolve("audit.log")))
                .contains("authoring.setFields")
                .contains("(2 fields)");
    }

    @Test
    void setFieldsRemovalDropsTheKeyLosslesslyAndBumpsTheRevision() throws Exception {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        String fixture = """
                # a comment worth keeping
                wand:
                  material: STICK # inline comment
                  display_name: "Old Name"
                  # trailing note
                """;
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, fixture));

        ApiResult<AuthoringApi.DocumentView> reset = api.setFields(editor(),
                new AuthoringApi.SetFieldsRequest("demo", "items/wand.yml", 1, List.of(
                        AuthoringApi.FieldEdit.removal(List.of("wand", "display_name")))));

        assertThat(reset.ok()).isTrue();
        assertThat(reset.value().revision()).isEqualTo(2);
        assertThat(Files.readString(temp.resolve("packs/demo/items/wand.yml"))).isEqualTo("""
                # a comment worth keeping
                wand:
                  material: STICK # inline comment
                  # trailing note
                """);
        assertThat(reset.value().parsed()).containsOnlyKeys("wand");
        assertThat(Files.readString(temp.resolve("audit.log"))).contains("authoring.setFields");
    }

    @Test
    void staleRemovalConflictsAndLeavesTheFileUnchanged() throws Exception {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        String fixture = "wand:\n  material: STICK\n  display_name: Old Name\n";
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, fixture));

        ApiResult<AuthoringApi.DocumentView> stale = api.setFields(editor(),
                new AuthoringApi.SetFieldsRequest("demo", "items/wand.yml", 0, List.of(
                        AuthoringApi.FieldEdit.removal(List.of("wand", "display_name")))));

        assertThat(stale.ok()).isFalse();
        assertThat(stale.diagnostics()).hasSize(1);
        assertThat(stale.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
        assertThat(Files.readString(temp.resolve("packs/demo/items/wand.yml"))).isEqualTo(fixture);
        assertThat(api.getDocument("demo", "items/wand.yml").value().revision()).isEqualTo(1);
    }

    @Test
    void inheritingDocumentsReportEffectiveValuesAndProvenance() throws Exception {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        Files.createDirectories(temp.resolve("packs/demo/templates"));
        Files.writeString(temp.resolve("packs/demo/templates/base.yml"), """
                base:
                  material: DIAMOND_SWORD
                  lore:
                    - Forged
                    - Hard
                """);
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/sword.yml", 0, """
                        sword:
                          template: base
                          display_name: Blade
                          lore:
                            - Mine
                        """));

        AuthoringApi.DocumentView view = api.getDocument("demo", "items/sword.yml").value();
        Map<String, Object> sword = inheritanceOf(view, "sword");

        assertThat(sword)
                .containsEntry("template", "base")
                .containsEntry("chain", List.of("demo:base"))
                // the template's values, including the key the item overrides;
                // the item's own display_name is not inherited at all
                .containsEntry("inherited", Map.of(
                        "material", "DIAMOND_SWORD",
                        "lore", List.of("Forged", "Hard")))
                .containsEntry("sources", Map.of("material", "demo:base", "lore", "demo:base"))
                .containsEntry("templates", Map.of(
                        "demo:base", Map.of("pack", "demo", "path", "templates/base.yml")))
                .doesNotContainKey("failure");
    }

    @Test
    void unresolvedTemplateReportsAFailureAndInheritsNothing() {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/ghost.yml", 0, "ghost:\n  template: missing\n  material: PAPER\n"));

        Map<String, Object> ghost = inheritanceOf(api.getDocument("demo", "items/ghost.yml").value(), "ghost");

        assertThat(ghost)
                .containsEntry("template", "missing")
                .containsEntry("chain", List.of("demo:missing"))
                .containsEntry("inherited", Map.of())
                .containsEntry("sources", Map.of())
                .containsEntry("templates", Map.of());
        assertThat(ghost.get("failure")).isInstanceOf(String.class);
        assertThat((String) ghost.get("failure")).contains("unknown template").contains("demo:missing");
    }

    @Test
    void documentsWithoutATemplateCarryNoInheritanceEntry() {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/plain.yml", 0, "plain:\n  material: PAPER\n"));

        AuthoringApi.DocumentView view = api.getDocument("demo", "items/plain.yml").value();

        assertThat(view.computed()).doesNotContainKey("inheritance");
    }

    /** The per-root inheritance view that rides {@code computed} for a templated document. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> inheritanceOf(AuthoringApi.DocumentView view, String root) {
        Map<String, Object> inheritance = (Map<String, Object>) view.computed().get("inheritance");
        assertThat(inheritance).containsKey(root);
        return (Map<String, Object>) inheritance.get(root);
    }

    @Test
    void historyRecordsTheActingEditor() {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, "wand:\n  material: STICK\n"));

        List<AuthoringApi.RevisionInfo> history =
                api.getHistory("demo", "items/wand.yml", 10).value();

        assertThat(history).isNotEmpty();
        assertThat(history.get(history.size() - 1).actor()).isEqualTo("editor:session-1");
        assertThat(history.get(history.size() - 1).action()).isEqualTo("write");
    }

    @Test
    void createResourceScaffoldsAndAppliesTheDisplayName() throws Exception {
        AuthoringApi api = api();

        ApiResult<AuthoringApi.DocumentView> created = api.createResource(editor(),
                new AuthoringApi.CreateResourceRequest(
                        "demo", "ruby_wand", "Ruby Wand", null, null, true));

        assertThat(created.ok()).isTrue();
        assertThat(created.value().revision()).isEqualTo(1);
        assertThat(created.value().path()).isEqualTo("items/ruby_wand.yml");
        assertThat(created.value().raw())
                .contains("display_name: Ruby Wand")
                .contains("material: STICK");
        // one reload of exactly the created file, like the editor op queues
        assertThat(reloads.files).containsExactly(
                temp.resolve("packs").resolve("demo").resolve("items").resolve("ruby_wand.yml"));
    }

    @Test
    void deleteDocumentIsRevisionGuarded() throws Exception {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, "wand:\n  material: STICK\n"));

        ApiResult<Void> stale = api.deleteDocument(editor(),
                new AuthoringApi.DeleteDocumentRequest("demo", "items/wand.yml", 0));

        assertThat(stale.ok()).isFalse();
        assertThat(stale.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
        assertThat(temp.resolve("packs/demo/items/wand.yml")).exists();

        ApiResult<Void> deleted = api.deleteDocument(editor(),
                new AuthoringApi.DeleteDocumentRequest("demo", "items/wand.yml", 1));

        assertThat(deleted.ok()).isTrue();
        assertThat(temp.resolve("packs/demo/items/wand.yml")).doesNotExist();
    }

    @Test
    void getDocumentReportsMissingFilesAsMtr0009AndComputesNothingByDefault() {
        AuthoringApi api = api();
        api.createPack(editor(), "demo").value();

        ApiResult<AuthoringApi.DocumentView> missing =
                api.getDocument("demo", "items/ghost.yml");

        assertThat(missing.ok()).isFalse();
        assertThat(missing.diagnostics().get(0).code().code())
                .isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE.code());
    }

    @Test
    void computedMetadataRidesTheDocumentView() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuthoringApiImpl api = new AuthoringApiImpl(
                documents, new AuditLog(temp.resolve("audit.log")), packsRoot,
                new ModelTransactionService(documents, packsRoot, new AuditLog(temp.resolve("audit.log"))),
                (pack, path, parsed) -> java.util.Map.of("allocated", true),
                () -> null, reloads);
        api.createPack(editor(), "demo").value();
        api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(
                "demo", "items/wand.yml", 0, "wand:\n  material: STICK\n"));

        AuthoringApi.DocumentView view = api.getDocument("demo", "items/wand.yml").value();

        assertThat(view.computed()).containsEntry("allocated", true);
        assertThat(view.parsed()).containsKey("wand");
    }

    @Test
    void summariesReadEveryCanonicalRootResolveCrossPackAssetsAndRemainReadOnly() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Path other = packsRoot.resolve("other");
        Files.createDirectories(demo.resolve("items"));
        Files.createDirectories(other.resolve("assets/textures/item"));
        Files.createDirectories(other.resolve("assets/models/item"));
        Files.createDirectories(other.resolve(".materione/thumbnails/item"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        Files.writeString(other.resolve("pack.yml"), "id: other\n");
        Files.writeString(demo.resolve("items/catalog.yml"), """
                missing:
                  material: DIAMOND
                  texture: other:missing
                ruby:
                  material: PAPER
                  display_name: Ruby
                  texture: other:item/ruby
                wand:
                  material: STICK
                  display_name: Wand
                  model: other:item/wand
                  texture: other:item/skin
                missing_model:
                  material: PAPER
                  texture: other:item/skin
                  model: other:item/missing
                escape:
                  material: STICK
                  texture: other:../../outside
                """);
        Files.writeString(other.resolve("assets/textures/item/ruby.png"), "png");
        Files.writeString(other.resolve("assets/textures/item/skin.png"), "png");
        Files.writeString(other.resolve("assets/models/item/wand.json"), "{}");
        Files.writeString(other.resolve(".materione/thumbnails/item/wand.png"), "png");

        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryItemType()));

        ApiResult<AuthoringApi.ResourceSummaryPage> first = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 2);
        assertThat(first.ok()).isTrue();
        assertThat(first.value().total()).isEqualTo(5);
        assertThat(first.value().resources())
                .extracting(AuthoringApi.ResourceSummary::resourceId)
                .containsExactly("escape", "missing");
        assertThat(first.value().resources().get(0).health())
                .isEqualTo(AuthoringApi.ResourceHealth.ERROR);
        assertThat(first.value().resources().get(0).visual()).isNull();
        assertThat(first.value().nextOffset()).isEqualTo(2);

        ApiResult<AuthoringApi.ResourceSummaryPage> remaining = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "ruby"), 0, 10);
        assertThat(remaining.value().total()).isEqualTo(1);
        AuthoringApi.ResourceSummary ruby = remaining.value().resources().get(0);
        assertThat(ruby.name()).isEqualTo("Ruby");
        assertThat(ruby.material()).isEqualTo("PAPER");
        assertThat(ruby.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "other", "assets/textures/item/ruby.png", AuthoringApi.VisualKind.TEXTURE));
        assertThat(ruby.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);

        AuthoringApi.ResourceSummary wand = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "wand"), 0, 10)
                .value().resources().get(0);
        assertThat(wand.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "other", ".materione/thumbnails/item/wand.png",
                AuthoringApi.VisualKind.MODEL_THUMBNAIL));
        // the live render reads the model file itself, not the cached thumbnail
        assertThat(wand.render()).isEqualTo(new AuthoringApi.ResourceRender(
                "other", "assets/models/item/wand.json", null));
        assertThat(ruby.render()).isNull();

        AuthoringApi.ResourceSummary missingModel = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "missing_model"), 0, 10)
                .value().resources().get(0);
        assertThat(missingModel.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "other", "assets/textures/item/skin.png", AuthoringApi.VisualKind.TEXTURE));
        assertThat(missingModel.health()).isEqualTo(AuthoringApi.ResourceHealth.ERROR);
        assertThat(missingModel.healthMessage()).contains("model asset is missing");
        assertThat(missingModel.render()).isNull();

        assertThat(documents.listResourceDocuments("demo", "blocks")).isEmpty();
        assertThat(temp.resolve("revisions")).doesNotExist();
        assertThat(temp.resolve("audit.log")).doesNotExist();
        assertThat(reloads.files).isEmpty();
    }

    @Test
    void generatedModelsReachTheSummaryAsAnInlineRender() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Files.createDirectories(demo.resolve("items"));
        Files.createDirectories(demo.resolve("assets/textures/block"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        Files.writeString(demo.resolve("items/shapes.yml"), """
                crate:
                  material: PAPER
                  texture: demo:block/crate
                  shape: block/cube_all
                """);
        Files.writeString(demo.resolve("assets/textures/block/crate.png"), "png");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryItemType()));

        AuthoringApi.ResourceSummary crate = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 10)
                .value().resources().get(0);

        assertThat(crate.render()).isEqualTo(new AuthoringApi.ResourceRender("demo", null, Map.of(
                "parent", "block/cube_all", "textures", Map.of("all", "demo:block/crate"))));
        assertThat(crate.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "demo", "assets/textures/block/crate.png", AuthoringApi.VisualKind.TEXTURE));
    }

    @Test
    void glyphSummariesResolveBitmapAndAliasSourcesWithinOneBoundedProjection() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Path other = packsRoot.resolve("other");
        Files.createDirectories(demo.resolve("glyphs"));
        Files.createDirectories(demo.resolve("assets/textures/gui"));
        Files.createDirectories(other.resolve("glyphs"));
        Files.createDirectories(other.resolve("assets/textures/gui"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        Files.writeString(other.resolve("pack.yml"), "id: other\n");
        Files.writeString(demo.resolve("glyphs/catalog.yml"), """
                heart:
                  texture: gui/heart
                heart_alias:
                  reference: demo:heart
                chained_alias:
                  reference: demo:heart_alias
                remote_alias:
                  reference: other:remote
                sheet:
                  texture: other:gui/sheet.png
                  rows: 2
                  columns: 3
                sheet_cell_2:
                  reference: demo:sheet
                  index: 2
                missing:
                  texture: gui/missing
                missing_alias:
                  reference: demo:absent
                cycle_a:
                  reference: demo:cycle_b
                cycle_b:
                  reference: demo:cycle_a
                """);
        Files.writeString(other.resolve("glyphs/catalog.yml"), """
                remote:
                  texture: gui/remote
                """);
        Files.writeString(demo.resolve("assets/textures/gui/heart.png"), "png");
        Files.writeString(other.resolve("assets/textures/gui/sheet.png"), "png");
        Files.writeString(other.resolve("assets/textures/gui/remote.png"), "png");

        Path revisions = temp.resolve("revisions");
        DocumentStore documents = new DocumentStore(packsRoot, revisions);
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        SummaryGlyphType glyphType = new SummaryGlyphType();
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(glyphType));

        AuthoringApi.ResourceSummary heart = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "heart"), 0, 10)
                .value().resources().get(0);
        assertThat(heart.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "demo", "assets/textures/gui/heart.png", AuthoringApi.VisualKind.TEXTURE));
        assertThat(heart.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);

        AuthoringApi.ResourceSummary heartAlias = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "heart_alias"), 0, 10)
                .value().resources().get(0);
        assertThat(heartAlias.visual()).isEqualTo(heart.visual());
        assertThat(heartAlias.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);

        AuthoringApi.ResourceSummary chainedAlias = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "chained_alias"), 0, 10)
                .value().resources().get(0);
        assertThat(chainedAlias.visual()).isEqualTo(heart.visual());
        assertThat(chainedAlias.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);

        AuthoringApi.ResourceSummary remoteAlias = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "remote_alias"), 0, 10)
                .value().resources().get(0);
        assertThat(remoteAlias.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "other", "assets/textures/gui/remote.png", AuthoringApi.VisualKind.TEXTURE));
        assertThat(remoteAlias.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);

        AuthoringApi.ResourceSummary sheet = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "sheet"), 0, 10)
                .value().resources().get(0);
        assertThat(sheet.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "other", "assets/textures/gui/sheet.png", AuthoringApi.VisualKind.TEXTURE));
        assertThat(sheet.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);

        AuthoringApi.ResourceSummary sheetCell = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "sheet_cell_2"), 0, 10)
                .value().resources().get(0);
        assertThat(sheetCell.visual()).isEqualTo(sheet.visual());

        AuthoringApi.ResourceSummary missing = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "missing"), 0, 10)
                .value().resources().get(0);
        assertThat(missing.visual()).isNull();
        assertThat(missing.health()).isEqualTo(AuthoringApi.ResourceHealth.ERROR);
        assertThat(missing.healthMessage()).contains("referenced texture asset is missing");

        AuthoringApi.ResourceSummary missingAlias = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "missing_alias"), 0, 10)
                .value().resources().get(0);
        assertThat(missingAlias.visual()).isNull();
        assertThat(missingAlias.health()).isEqualTo(AuthoringApi.ResourceHealth.ERROR);
        assertThat(missingAlias.healthMessage()).contains("presentation reference target is missing");

        AuthoringApi.ResourceSummary cycle = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "glyphs", "cycle_a"), 0, 10)
                .value().resources().get(0);
        assertThat(cycle.visual()).isNull();
        assertThat(cycle.health()).isEqualTo(AuthoringApi.ResourceHealth.ERROR);
        assertThat(cycle.healthMessage()).contains("presentation reference cycle");

        assertThat(revisions).doesNotExist();
        assertThat(temp.resolve("audit.log")).doesNotExist();
        assertThat(reloads.files).isEmpty();
        assertThat(glyphType.parseCalls).isEqualTo(11);
    }

    @Test
    void unavailableAliasPreviewsDoNotClaimCanonicalContentIsInvalid() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Files.createDirectories(demo.resolve("glyphs"));
        Files.createDirectories(demo.resolve("assets/textures/gui"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        StringBuilder glyphs = new StringBuilder("""
                source:
                  texture: gui/heart
                vanilla:
                  texture: minecraft:gui/heart
                vanilla_alias:
                  reference: demo:vanilla
                """);
        for (int index = 0; index < 34; index++) {
            glyphs.append("link_").append(index).append(":\n  reference: demo:")
                    .append(index == 33 ? "source" : "link_" + (index + 1)).append('\n');
        }
        Files.writeString(demo.resolve("glyphs/catalog.yml"), glyphs);
        Files.writeString(demo.resolve("assets/textures/gui/heart.png"), "png");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryGlyphType()));

        for (String id : List.of("link_0", "vanilla_alias")) {
            AuthoringApi.ResourceSummary summary = api.listResourceSummaries(
                    new AuthoringApi.ResourceFilter("demo", "glyphs", id), 0, 10)
                    .value().resources().get(0);
            assertThat(summary.visual()).isNull();
            assertThat(summary.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);
            assertThat(summary.healthMessage()).contains("visual reference could not be safely checked");
        }
    }

    @Test
    void summariesUseCurrentFilesAndNeverPromoteUnknownPipelineHealthToOk() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path pack = packsRoot.resolve("demo");
        Files.createDirectories(pack.resolve("items"));
        Files.writeString(pack.resolve("pack.yml"), "id: demo\n");
        Path source = pack.resolve("items/wand.yml");
        Files.writeString(source, "wand:\n  material: STICK\n  display_name: Old\n");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryItemType()));

        AuthoringApi.ResourceSummary before = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 10)
                .value().resources().get(0);
        Files.writeString(source, "wand:\n  material: BLAZE_ROD\n  display_name: New\n");
        ((AuthoringApiImpl) api).publishChange("demo", "items/wand.yml", false);
        AuthoringApi.ResourceSummary after = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 10)
                .value().resources().get(0);

        assertThat(before.name()).isEqualTo("Old");
        assertThat(after.name()).isEqualTo("New");
        assertThat(after.material()).isEqualTo("BLAZE_ROD");
        assertThat(after.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);
    }

    @Test
    void summaryPagesReuseTheProjectionAndRecheckAssetReferences() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Path other = packsRoot.resolve("other");
        Files.createDirectories(demo.resolve("items"));
        Files.createDirectories(other.resolve("assets/textures/item"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        Files.writeString(other.resolve("pack.yml"), "id: other\n");
        Path source = demo.resolve("items/catalog.yml");
        Files.writeString(source, """
                alpha:
                  material: PAPER
                  display_name: Alpha
                  texture: other:item/skin
                beta:
                  material: STICK
                  display_name: Beta
                """);

        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        SummaryItemType itemType = new SummaryItemType();
        AuthoringApiImpl api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(itemType));

        AuthoringApi.ResourceSummaryPage first = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 1).value();
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.resources().get(0).resourceId()).isEqualTo("alpha");
        assertThat(first.resources().get(0).health()).isEqualTo(AuthoringApi.ResourceHealth.ERROR);
        assertThat(itemType.parseCalls).isEqualTo(2);

        AuthoringApi.ResourceSummaryPage second = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 1, 1).value();
        assertThat(second.resources().get(0).resourceId()).isEqualTo("beta");
        assertThat(itemType.parseCalls).isEqualTo(2);

        Files.writeString(other.resolve("assets/textures/item/skin.png"), "png");
        AuthoringApi.ResourceSummary assetUpdated = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "alpha"), 0, 1)
                .value().resources().get(0);
        assertThat(assetUpdated.visual()).isEqualTo(new AuthoringApi.ResourceVisual(
                "other", "assets/textures/item/skin.png", AuthoringApi.VisualKind.TEXTURE));
        assertThat(assetUpdated.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);
        assertThat(itemType.parseCalls).isEqualTo(2);

        Files.writeString(source, """
                alpha:
                  material: PAPER
                  display_name: Changed
                  texture: other:item/skin
                beta:
                  material: STICK
                  display_name: Beta
                """);
        AuthoringApi.ResourceSummary beforeChangeEvent = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "alpha"), 0, 1)
                .value().resources().get(0);
        assertThat(beforeChangeEvent.name()).isEqualTo("Alpha");
        assertThat(itemType.parseCalls).isEqualTo(2);

        api.publishChange("demo", "items/catalog.yml", false);
        AuthoringApi.ResourceSummary canonicalUpdated = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "alpha"), 0, 1)
                .value().resources().get(0);
        assertThat(canonicalUpdated.name()).isEqualTo("Changed");
        assertThat(itemType.parseCalls).isEqualTo(4);

        AuthoringApi.DocumentView current = api.getDocument("demo", "items/catalog.yml").value();
        ApiResult<AuthoringApi.DocumentView> saved = api.putDocument(editor(),
                new AuthoringApi.PutDocumentRequest(
                        "demo", "items/catalog.yml", current.revision(), """
                                alpha:
                                  material: PAPER
                                  display_name: API Updated
                                  texture: other:item/skin
                                beta:
                                  material: STICK
                                  display_name: Beta
                                """));
        assertThat(saved.ok()).isTrue();
        AuthoringApi.ResourceSummary apiUpdated = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", "alpha"), 0, 1)
                .value().resources().get(0);
        assertThat(apiUpdated.name()).isEqualTo("API Updated");
        assertThat(itemType.parseCalls).isEqualTo(6);
    }

    @Test
    void summaryProjectionIncludesEveryCanonicalContentRootAndTypeFilter() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Files.createDirectories(demo.resolve("items"));
        Files.createDirectories(demo.resolve("blocks"));
        Files.createDirectories(demo.resolve("templates"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        Files.writeString(demo.resolve("items/wand.yml"), "wand:\n  material: STICK\n");
        Files.writeString(demo.resolve("blocks/stone.yml"), "stone:\n  material: STONE\n");
        Files.writeString(demo.resolve("templates/base.yml"), "base:\n  material: PAPER\n");

        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryItemType()));

        AuthoringApi.ResourceSummaryPage all = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", null, null), 0, 10).value();
        assertThat(all.total()).isEqualTo(3);
        assertThat(all.resources())
                .extracting(AuthoringApi.ResourceSummary::type)
                .containsExactly("blocks", "items", "templates");

        AuthoringApi.ResourceSummaryPage blocks = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "blocks", null), 0, 10).value();
        assertThat(blocks.total()).isEqualTo(1);
        assertThat(blocks.resources().get(0).resourceId()).isEqualTo("stone");
    }

    @Test
    void watcherInvalidatesAndRecursivelyRegistersPopulatedMovedSubtrees() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Files.createDirectories(demo.resolve("items"));
        Files.createDirectories(demo.resolve("templates"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");

        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApiImpl api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryItemType()));
        assertThat(api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 10)
                .value().total()).isZero();

        AtomicInteger createdDirectories = new AtomicInteger();
        CopyOnWriteArrayList<String> changedPaths = new CopyOnWriteArrayList<>();
        PackWatcher watcher = new PackWatcher(
                packsRoot,
                documents,
                change -> {
                    api.publishChange(change.pack(), change.path(), change.deleted());
                    changedPaths.add(change.path());
                },
                () -> {
                    createdDirectories.incrementAndGet();
                    api.invalidateResourceSummaries();
                });
        Thread watcherThread = new Thread(watcher::runLoop, "summary-pack-watcher-test");
        watcherThread.setDaemon(true);
        watcherThread.start();
        try {
            Path incomingItems = temp.resolve("incoming/items/moved/nested");
            Path incomingTemplates = temp.resolve("incoming/templates/moved/nested");
            Files.createDirectories(incomingItems);
            Files.createDirectories(incomingTemplates);
            Files.writeString(incomingItems.resolve("catalog.yml"), """
                    item_template:
                      template: base
                    other:
                      template: base
                    """);
            Files.writeString(incomingTemplates.resolve("base.yml"), """
                    base:
                      material: PAPER
                      display_name: Base Name
                    """);

            Path movedItem = demo.resolve("items/moved/nested/catalog.yml");
            Path movedTemplate = demo.resolve("templates/moved/nested/base.yml");
            Files.move(incomingItems.getParent(), demo.resolve("items/moved"));
            Files.move(incomingTemplates.getParent(), demo.resolve("templates/moved"));

            await(() -> createdDirectories.get() >= 2
                    && changedPaths.contains("items/moved/nested/catalog.yml")
                    && changedPaths.contains("templates/moved/nested/base.yml"));
            AuthoringApi.ResourceSummaryPage moved = api.listResourceSummaries(
                    new AuthoringApi.ResourceFilter("demo", "items", null), 0, 10).value();
            assertThat(moved.resources())
                    .extracting(AuthoringApi.ResourceSummary::resourceId)
                    .containsExactly("item_template", "other");
            assertThat(moved.resources())
                    .extracting(AuthoringApi.ResourceSummary::name)
                    .containsExactly("Base Name", "Base Name");

            changedPaths.clear();
            Files.writeString(movedItem, """
                    item_template:
                      template: base
                      display_name: Item Updated
                    other:
                      template: base
                    """);
            await(() -> changedPaths.contains("items/moved/nested/catalog.yml"));
            AuthoringApi.ResourceSummary itemUpdated = api.listResourceSummaries(
                    new AuthoringApi.ResourceFilter("demo", "items", "item_template"), 0, 1)
                    .value().resources().get(0);
            assertThat(itemUpdated.name()).isEqualTo("Item Updated");

            Files.writeString(movedTemplate, """
                    base:
                      material: PAPER
                      display_name: Template Updated
                    """);
            await(() -> changedPaths.contains("templates/moved/nested/base.yml"));
            AuthoringApi.ResourceSummary templateUpdated = api.listResourceSummaries(
                    new AuthoringApi.ResourceFilter("demo", "items", "other"), 0, 1)
                    .value().resources().get(0);
            assertThat(templateUpdated.name()).isEqualTo("Template Updated");
        } finally {
            watcher.close();
            watcherThread.join(2_000);
        }
    }

    @Test
    void projectionBudgetStopsBuildAndCachesTheVisibleFailureAcrossPages() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Files.createDirectories(demo.resolve("items"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        Path source = demo.resolve("items/catalog.yml");
        Files.writeString(source, """
                first:
                  material: PAPER
                  display_name: First
                second:
                  material: STICK
                  display_name: Second
                """);

        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        SummaryItemType itemType = new SummaryItemType();
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(itemType), 900);

        ApiResult<AuthoringApi.ResourceSummaryPage> firstPage = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 1);
        assertThat(firstPage.ok()).isFalse();
        assertThat(firstPage.diagnostics().get(0).code().code()).isEqualTo("MTR-0051");
        assertThat(firstPage.diagnostics().get(0).message()).contains("bounded read/projection budget");
        assertThat(itemType.parseCalls).isEqualTo(1);

        ApiResult<AuthoringApi.ResourceSummaryPage> nextPage = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 1, 1);
        assertThat(nextPage.ok()).isFalse();
        assertThat(nextPage.diagnostics().get(0).code().code()).isEqualTo("MTR-0051");
        assertThat(itemType.parseCalls).isEqualTo(1);
        assertThat(api.listResources(new AuthoringApi.ResourceFilter("demo", "items", null))
                .value()).hasSize(2);
        assertThat(Files.readString(source)).contains("first:", "second:");
        assertThat(temp.resolve("revisions")).doesNotExist();
        assertThat(temp.resolve("audit.log")).doesNotExist();
    }

    @Test
    void oversizedCanonicalDocumentIsReportedWithoutAnUnboundedRead() throws Exception {
        Path packsRoot = temp.resolve("packs");
        Path demo = packsRoot.resolve("demo");
        Files.createDirectories(demo.resolve("items"));
        Files.writeString(demo.resolve("pack.yml"), "id: demo\n");
        String oversized = "large:\n  value: \"" + "x".repeat(9 * 1024 * 1024) + "\"\n";
        Files.writeString(demo.resolve("items/large.yml"), oversized);

        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        DocumentStore.ResourceDocument oversizedRead =
                documents.listResourceDocuments("demo", "items").get(0);
        assertThat(oversizedRead.resource().resourceId()).isEqualTo("<unreadable>");
        assertThat(oversizedRead.readError()).contains("bounded summary read limit");
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        AuthoringApi api = new AuthoringApiImpl(
                documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit),
                null, () -> null, reloads, new AssetStore(packsRoot, audit),
                () -> List.of(new SummaryItemType()));

        AuthoringApi.ResourceSummary summary = api.listResourceSummaries(
                new AuthoringApi.ResourceFilter("demo", "items", null), 0, 10)
                .value().resources().get(0);
        assertThat(summary.resourceId()).isEqualTo("<unreadable>");
        assertThat(summary.health()).isEqualTo(AuthoringApi.ResourceHealth.UNKNOWN);
        assertThat(summary.healthMessage()).contains("bounded summary read limit");
        assertThat(Files.size(demo.resolve("items/large.yml"))).isEqualTo(oversized.length());
        assertThat(temp.resolve("revisions")).doesNotExist();
        assertThat(temp.resolve("audit.log")).doesNotExist();
    }

    @Test
    void changeSubscriptionFeedsWatcherEvents() throws Exception {
        AuthoringApiImpl api = (AuthoringApiImpl) api();
        List<AuthoringApi.DocumentChangeEvent> seen = new ArrayList<>();
        AutoCloseable subscription = api.subscribeChanges(seen::add);

        api.publishChange("demo", "items/wand.yml", false);
        api.publishChange("demo", "items/gone.yml", true);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).isEqualTo(new AuthoringApi.DocumentChangeEvent(
                "demo", "items/wand.yml", false));
        assertThat(seen.get(1).deleted()).isTrue();
        subscription.close();
        api.publishChange("demo", "items/wand.yml", false);
        assertThat(seen).hasSize(2);
    }

    /** Records every reload the hook fired, with its scope shape. */
    private static final class RecordingReloads implements ReloadHook {
        final List<Path> files = new ArrayList<>();
        final List<String> packs = new ArrayList<>();

        @Override
        public void afterChange(dev.materione.api.auth.ActorContext actor, String pack, Path changedFile) {
            files.add(changedFile);
            packs.add(pack);
        }
    }

    private static final class SummaryGlyphType implements ContentType {
        private int parseCalls;

        @Override
        public String name() {
            return "glyph";
        }

        @Override
        public String directory() {
            return "glyphs";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Glyph", "", List.of(), "test");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition,
                Map<String, Map<String, Object>> templates) {
            parseCalls++;
            return Optional.of(new LinkedHashMap<>(definition));
        }

        @Override
        public Optional<ResourcePresentation> presentation(Object parsedDefinition) {
            if (parsedDefinition instanceof Map<?, ?> parsed) {
                if (parsed.get("texture") instanceof String texture) {
                    return Optional.of(new ResourcePresentation(null, null, texture, null));
                }
                if (parsed.get("reference") instanceof String reference) {
                    return Optional.of(new ResourcePresentation(null, null, null, null,
                            new ResourcePresentation.Reference(
                                    "glyphs", NamespacedId.parse(reference))));
                }
            }
            return Optional.empty();
        }

        @Override
        public void compile(
                Object definition, NamespacedId allocatedModelId,
                PackEmitter emitter, Allocations allocations) {}
    }

    private static final class SummaryItemType implements ContentType {
        private int parseCalls;

        @Override
        public String name() {
            return "item";
        }

        @Override
        public String directory() {
            return "items";
        }

        @Override
        public SelfDescription describe() {
            return new SelfDescription("Item", "", List.of(
                    new ParameterSchema("display_name", ParameterType.STRING, false,
                            null, null, null, null)
                            .withEditor(new ParameterSchema.EditorMetadata(
                                    "Display name", "identity", 0, false, "text", "", "")),
                    new ParameterSchema("material", ParameterType.STRING, true,
                            null, null, null, null)), "test");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition,
                Map<String, Map<String, Object>> templates) {
            parseCalls++;
            var merged = new LinkedHashMap<String, Object>();
            Object template = definition.get("template");
            if (template instanceof String reference) {
                String key = reference.contains(":") ? reference : id.namespace() + ":" + reference;
                Map<String, Object> base = templates.get(key);
                if (base == null) {
                    return Optional.empty();
                }
                merged.putAll(base);
            }
            merged.putAll(definition);
            return merged.get("material") instanceof String ? Optional.of(merged) : Optional.empty();
        }

        @Override
        public Optional<ResourcePresentation> presentation(Object parsedDefinition) {
            if (!(parsedDefinition instanceof Map<?, ?> parsed)) {
                return Optional.empty();
            }
            // `shape:` stands in for a type that compiles a generated model
            Map<String, Object> generated = parsed.get("shape") instanceof String shape
                    ? Map.of("parent", shape, "textures", Map.of("all", String.valueOf(parsed.get("texture"))))
                    : null;
            return Optional.of(new ResourcePresentation(
                    (String) parsed.get("display_name"),
                    (String) parsed.get("material"),
                    (String) parsed.get("texture"),
                    (String) parsed.get("model"),
                    null,
                    generated));
        }

        @Override
        public void compile(
                Object definition, NamespacedId allocatedModelId,
                PackEmitter emitter, Allocations allocations) {}
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
