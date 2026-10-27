package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.BulkKind;
import dev.materione.api.authoring.AuthoringApi.BulkOperation;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bulk authoring is all-or-none and revision-checked; reference queries say what they did not read. */
class BulkAuthoringTest {

    @TempDir
    Path temp;

    private AuthoringApi api;

    @BeforeEach
    void setUp() {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        api = new AuthoringApiImpl(documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit), null, () -> null, (a, p, f) -> { });
        api.createPack(editor(), "demo").value();
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    private int seed(String path, String content) {
        return api.putDocument(editor(), new AuthoringApi.PutDocumentRequest("demo", path, 0, content))
                .value().revision();
    }

    private static BulkOperation set(String path, int rev, String field, Object value) {
        return new BulkOperation(BulkKind.SET, path, rev, null, null, null, null, null,
                List.of(new FieldEdit(List.of("root", field), value)));
    }

    private String read(String path) throws Exception {
        return Files.readString(temp.resolve("packs/demo").resolve(path));
    }

    @Test
    void appliesEveryDocumentTogetherAndPatchesOnlyTheTouchedLines() throws Exception {
        int a = seed("items/a.yml", "a:\n  material: STICK # keep\n  texture: gem\n");
        int b = seed("items/b.yml", "b:\n  material: STICK\n  texture: gem\n");

        List<FieldEdit> edit = List.of(new FieldEdit(List.of("a", "texture"), "ruby"));
        List<FieldEdit> editB = List.of(new FieldEdit(List.of("b", "texture"), "ruby"));
        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.SET, "items/a.yml", a, null, null, null, null, null, edit),
                new BulkOperation(BulkKind.SET, "items/b.yml", b, null, null, null, null, null, editB)), false));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().applied()).isTrue();
        assertThat(read("items/a.yml")).isEqualTo("a:\n  material: STICK # keep\n  texture: ruby\n");
        assertThat(read("items/b.yml")).contains("texture: ruby");
        assertThat(result.value().documents()).allSatisfy(doc -> {
            assertThat(doc.revision()).isEqualTo(2);
            assertThat(doc.changes()).hasSize(1);
            assertThat(doc.changes().get(0).before()).isEqualTo("gem");
            assertThat(doc.changes().get(0).after()).isEqualTo("ruby");
        });
    }

    @Test
    void aStaleRevisionOnOneDocumentWritesNothingAnywhere() throws Exception {
        int a = seed("items/a.yml", "a:\n  texture: gem\n");
        seed("items/b.yml", "b:\n  texture: gem\n");

        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.SET, "items/a.yml", a, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("a", "texture"), "ruby"))),
                new BulkOperation(BulkKind.SET, "items/b.yml", 7, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("b", "texture"), "ruby")))), false));

        assertThat(result.ok()).isFalse();
        assertThat(result.diagnostics().get(0).code().code()).isEqualTo("MTR-0034");
        assertThat(result.diagnostics().get(0).message()).contains("items/b.yml");
        assertThat(read("items/a.yml")).isEqualTo("a:\n  texture: gem\n");
    }

    @Test
    void anUnpatchableDocumentRollsBackTheOthersBeforeAnyWrite() throws Exception {
        int a = seed("items/a.yml", "a:\n  texture: gem\n");
        int b = seed("items/b.yml", "b:\n  tags: [x, y]\n");

        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.SET, "items/a.yml", a, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("a", "texture"), "ruby"))),
                new BulkOperation(BulkKind.SET, "items/missing.yml", 1, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("m", "texture"), "ruby")))), false));

        assertThat(result.ok()).isFalse();
        assertThat(read("items/a.yml")).isEqualTo("a:\n  texture: gem\n");
        assertThat(b).isEqualTo(1);
    }

    @Test
    void dryRunReportsTheImpactAndWritesNothing() throws Exception {
        int a = seed("items/a.yml", "a:\n  texture: gem\n");

        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.SET, "items/a.yml", a, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("a", "texture"), "ruby")))), true));

        assertThat(result.value().applied()).isFalse();
        assertThat(result.value().documents().get(0).changes()).hasSize(1);
        assertThat(read("items/a.yml")).isEqualTo("a:\n  texture: gem\n");
    }

    @Test
    void anEditThatChangesNothingIsReportedUnchangedAndNotWritten() throws Exception {
        int a = seed("items/a.yml", "a:\n  texture: gem\n");

        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.SET, "items/a.yml", a, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("a", "texture"), "gem")))), false));

        assertThat(result.value().documents().get(0).unchanged()).isTrue();
        assertThat(result.value().applied()).isFalse();
        assertThat(api.getDocument("demo", "items/a.yml").value().revision()).isEqualTo(a);
    }

    @Test
    void duplicatesVariantsAndCreatesARelatedRecipeInOneChange() throws Exception {
        seed("items/blade.yml", "blade:\n  material: IRON_SWORD # base\n  texture: steel\n  display_name: \"Blade\"\n");

        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.DUPLICATE, null, 0, null, null, "blade_red", null, "items/blade.yml",
                        List.of(new FieldEdit(List.of("blade_red", "texture"), "red"))),
                new BulkOperation(BulkKind.DUPLICATE, null, 0, null, null, "blade_blue", null, "items/blade.yml",
                        List.of(new FieldEdit(List.of("blade_blue", "texture"), "blue"))),
                new BulkOperation(BulkKind.CREATE, null, 0, "recipes", null, "blade_recipe", null, null,
                        List.of())), false));

        assertThat(result.ok()).as(String.valueOf(result.diagnostics())).isTrue();
        assertThat(read("items/blade_red.yml")).contains("blade_red:", "IRON_SWORD # base", "texture: red");
        assertThat(read("items/blade_blue.yml")).contains("texture: blue");
        assertThat(temp.resolve("packs/demo/recipes/blade_recipe.yml")).exists();
    }

    @Test
    void aCreateThatCollidesLeavesEveryOtherDocumentUntouched() throws Exception {
        int a = seed("items/a.yml", "a:\n  texture: gem\n");
        seed("items/taken.yml", "taken:\n  material: STICK\n");

        ApiResult<AuthoringApi.BulkResult> result = api.bulk(editor(), new AuthoringApi.BulkRequest("demo", List.of(
                new BulkOperation(BulkKind.SET, "items/a.yml", a, null, null, null, null, null,
                        List.of(new FieldEdit(List.of("a", "texture"), "ruby"))),
                new BulkOperation(BulkKind.DUPLICATE, null, 0, null, null, "taken", null, "items/a.yml",
                        List.of())), false));

        assertThat(result.ok()).isFalse();
        assertThat(read("items/a.yml")).isEqualTo("a:\n  texture: gem\n");
    }

    @Test
    void rejectsMoreOperationsThanTheCapAndDuplicateTargets() {
        int a = seed("items/a.yml", "a:\n  texture: gem\n");
        BulkOperation same = set("items/a.yml", a, "texture", "x");

        ApiResult<AuthoringApi.BulkResult> twice = api.bulk(editor(),
                new AuthoringApi.BulkRequest("demo", List.of(same, same), false));
        assertThat(twice.ok()).isFalse();

        List<BulkOperation> many = java.util.Collections.nCopies(AuthoringApi.MAX_BULK_OPERATIONS + 1, same);
        assertThat(api.bulk(editor(), new AuthoringApi.BulkRequest("demo", many, false)).ok()).isFalse();
    }

    @Test
    void referencesFindBareAndQualifiedAssetSpellingsAndReportCoverage() {
        seed("items/a.yml", "a:\n  texture: gem\n");
        seed("items/b.yml", "b:\n  texture: \"demo:gem\"\n  model: gem_model\n");
        seed("items/c.yml", "c:\n  texture: other\n  note: gem for sale\n");
        seed("items/plain.yml", "plain:\n  material: STICK\n");

        ApiResult<AuthoringApi.ReferenceResult> result = api.findReferences(new AuthoringApi.ReferenceQuery(
                AuthoringApi.ReferenceKind.ASSET, "demo", "assets/textures/gem.png"));

        assertThat(result.ok()).isTrue();
        assertThat(result.value().hits()).extracting(hit -> hit.resource().resourceId())
                .containsExactlyInAnyOrder("a", "b");
        assertThat(result.value().hits().get(0).field()).containsExactly("texture");
        assertThat(result.value().coverage().documentsScanned()).isEqualTo(4);
        assertThat(result.value().coverage().notScanned()).isNotEmpty();
    }

    @Test
    void referencesToAResourceMatchByIdAndRejectBadQueries() {
        seed("items/a.yml", "a:\n  template: base\n");

        ApiResult<AuthoringApi.ReferenceResult> result = api.findReferences(new AuthoringApi.ReferenceQuery(
                AuthoringApi.ReferenceKind.RESOURCE, "demo", "base"));
        assertThat(result.value().hits()).hasSize(1);

        assertThat(api.findReferences(new AuthoringApi.ReferenceQuery(
                AuthoringApi.ReferenceKind.ASSET, "Bad Pack", "x")).ok()).isFalse();
    }
}
