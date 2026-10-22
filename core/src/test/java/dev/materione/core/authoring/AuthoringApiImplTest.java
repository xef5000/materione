package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
}
