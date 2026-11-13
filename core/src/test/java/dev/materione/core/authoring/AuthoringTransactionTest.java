package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.TransactionOperation;
import dev.materione.api.authoring.TransactionOperation.Create;
import dev.materione.api.authoring.TransactionOperation.DetachTemplate;
import dev.materione.api.authoring.TransactionOperation.Duplicate;
import dev.materione.api.authoring.TransactionOperation.Delete;
import dev.materione.api.authoring.TransactionOperation.Move;
import dev.materione.api.authoring.TransactionOperation.SaveAsTemplate;
import dev.materione.api.authoring.TransactionOperation.SetFields;
import dev.materione.api.authoring.TransactionRequest;
import dev.materione.api.authoring.TransactionResult;
import dev.materione.api.authoring.TransactionResult.Kind;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.api.authoring.TransactionResult.TransactionDocument;
import dev.materione.api.content.AllocatedRange;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.registry.SelfDescription;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.Documents;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.ModelTransactionService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The authoring transaction (codemode.md section 10, issue 341): one ordered, mixed edit list, planned in memory
 * under the store lock, validated, and either reported or written all-or-none. Core-level cases use the document,
 * template and field operations; the logic and GUI edits ride the same path and are covered with their real
 * implementations in the web module's {@code TransactionOpsTest}.
 */
class AuthoringTransactionTest {

    private static final String PACK = "demo";

    @TempDir
    Path temp;

    private AuthoringApiImpl api;
    private final List<String> reloads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        build(true);
        api.createPack(editor(), PACK).value();
        seed("items/a.yml", "# keep this comment\na:\n  material: STICK # inline\n  texture: gem\n");
        seed("items/b.yml", "b:\n  material: STICK\n  texture: gem\n");
        seed("items/c.yml", "c:\n  material: STICK\n");
        seed("items/dd.yml", "dd:\n  material: STICK\n");
        seed("items/untouched.yml", "untouched:\n  # never edited\n  material: BONE\n\n  texture:   spaced\n");
        seed("templates/base.yml", "base:\n  material: PAPER\n  lore:\n    - Forged\n");
        seed("items/t.yml", "t:\n  template: base\n  display_name: T\n");
        seed("items/alpha.yml", "alpha:\n  material: STICK\n");
        reloads.clear();
    }

    /** A fresh api over the same directories; {@code preview} wires the allocation preview the build stage needs. */
    private void build(boolean preview) {
        Path packsRoot = temp.resolve("packs");
        DocumentStore documents = new DocumentStore(packsRoot, temp.resolve("revisions"));
        AuditLog audit = new AuditLog(temp.resolve("audit.log"));
        api = new AuthoringApiImpl(documents, audit, packsRoot,
                new ModelTransactionService(documents, packsRoot, audit), null, () -> null,
                (actor, pack, file) -> reloads.add(pack + "|" + file.getFileName()),
                null, () -> List.of(new StubItems()));
        if (preview) {
            api.previewAllocations(FreshAllocations::new);
        }
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    private static ActorContext only(String node) {
        return new ActorContext("limited", ActorContext.Kind.EDITOR_SESSION, Optional.empty(), node::equals);
    }

    private int seed(String path, String content) {
        return api.putDocument(editor(), new AuthoringApi.PutDocumentRequest(PACK, path, 0, content))
                .value().revision();
    }

    private String read(String path) throws IOException {
        return Files.readString(temp.resolve("packs").resolve(PACK).resolve(path));
    }

    private ApiResult<TransactionResult> run(boolean dryRun, TransactionOperation... operations) {
        return run(editor(), dryRun, operations);
    }

    private ApiResult<TransactionResult> run(ActorContext actor, boolean dryRun, TransactionOperation... operations) {
        return api.transaction(actor, new TransactionRequest(PACK, List.of(operations), dryRun));
    }

    private static FieldEdit set(String root, String field, Object value) {
        return new FieldEdit(List.of(root, field), value);
    }

    /** The mixed list of the issue, without logic or GUI edits: ten operations over seven documents. */
    private List<TransactionOperation> mixedList() {
        return List.of(
                new Create(null, null, "ruby", "Ruby", List.of(set("ruby", "texture", "ruby"))),
                new Duplicate("items/a.yml", 1, null, "a_copy", List.of(set("a_copy", "texture", "copy"))),
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "ruby"))),
                new SetFields("items/ruby.yml", null, List.of(set("ruby", "texture", "ruby_final"))),
                new Move("items/c.yml", 1, "items/moved/c.yml"),
                new Delete("items/dd.yml", 1),
                new SaveAsTemplate("items/a.yml", 1, null, "from_a", List.of("material")),
                new DetachTemplate("items/t.yml", 1, null),
                new Create(null, "gear", "belt", "Belt", List.of()),
                new SetFields("items/untouched.yml", 1, List.of(set("untouched", "material", "BONE"))));
    }

    private TransactionResult value(ApiResult<TransactionResult> result) {
        assertThat(result.value()).as(String.valueOf(result.diagnostics())).isNotNull();
        return result.value();
    }

    private static TransactionDocument document(TransactionResult result, String path) {
        return result.documents().stream().filter(doc -> doc.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no document " + path + " in " + result.documents()));
    }

    // ------------------------------------------------------------------
    // dry run

    @Test
    void aMixedListDryRunsWithTheResultingDocumentsAndWritesNothing() throws Exception {
        List<String> before = snapshot();

        ApiResult<TransactionResult> dry = run(true, mixedList().toArray(TransactionOperation[]::new));

        assertThat(dry.ok()).as(String.valueOf(dry.diagnostics())).isTrue();
        TransactionResult result = dry.value();
        assertThat(result.accepted()).isTrue();
        assertThat(result.applied()).isFalse();
        assertThat(result.transactionId()).isNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(reloads).isEmpty();

        assertThat(document(result, "items/ruby.yml").kind()).isEqualTo(Kind.CREATED);
        assertThat(document(result, "items/ruby.yml").revision()).isEqualTo(1);
        assertThat(document(result, "items/ruby.yml").text()).contains("texture: ruby_final");
        assertThat(document(result, "items/ruby.yml").operations()).containsExactly(0, 3);
        assertThat(document(result, "items/a_copy.yml").text()).contains("a_copy:", "texture: copy", "# keep this comment");
        assertThat(document(result, "items/b.yml").kind()).isEqualTo(Kind.CHANGED);
        assertThat(document(result, "items/b.yml").previousRevision()).isEqualTo(1);
        assertThat(document(result, "items/b.yml").revision()).isEqualTo(2);
        assertThat(document(result, "items/moved/c.yml").kind()).isEqualTo(Kind.MOVED);
        assertThat(document(result, "items/moved/c.yml").movedFrom()).isEqualTo("items/c.yml");
        assertThat(document(result, "items/c.yml").kind()).isEqualTo(Kind.DELETED);
        assertThat(document(result, "items/c.yml").text()).isNull();
        assertThat(document(result, "items/dd.yml").kind()).isEqualTo(Kind.DELETED);
        assertThat(document(result, "templates/from_a.yml").kind()).isEqualTo(Kind.CREATED);
        assertThat(document(result, "templates/from_a.yml").text()).contains("from_a:", "material: STICK");
        assertThat(document(result, "items/t.yml").text()).doesNotContain("template:").contains("material: PAPER");
        assertThat(document(result, "items/gear/belt.yml").kind()).isEqualTo(Kind.CREATED);
        // an edit that changes nothing is reported, and is not a write
        assertThat(document(result, "items/untouched.yml").kind()).isEqualTo(Kind.UNCHANGED);
        assertThat(document(result, "items/untouched.yml").revision()).isEqualTo(1);
        assertThat(result.stages()).extracting(TransactionResult.Stage::id)
                .contains("syntax", "schema", "build", "behavior", "gui", "references", "platform");
    }

    /**
     * The property of the issue: whatever the list (valid, refused halfway, stale, malformed), a dry run leaves the
     * packs, the revision journals and the audit log byte-identical, creates no directory, and queues no reload.
     */
    @Test
    void aDryRunLeavesTheStoreByteIdenticalWhateverTheList() throws Exception {
        List<String> before = snapshot();
        Random random = new Random(341);
        String[] stored = {"items/a.yml", "items/b.yml", "items/c.yml", "items/dd.yml", "templates/base.yml"};
        String[] hostile = {"items/missing.yml", "items/new/nothing.yml", "items/alpha.yml"};
        int accepted = 0;
        int refused = 0;
        int fresh = 0;
        for (int round = 0; round < 80; round++) {
            List<TransactionOperation> operations = new ArrayList<>();
            int length = 1 + random.nextInt(5);
            for (int i = 0; i < length; i++) {
                // mostly well-formed operations on documents that exist; now and then a bad path or a stale revision
                String path = random.nextInt(8) == 0 ? hostile[random.nextInt(hostile.length)]
                        : stored[random.nextInt(stored.length)];
                String id = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
                Integer rev = random.nextInt(10) == 0 ? Integer.valueOf(9) : (random.nextInt(10) == 0 ? null : 1);
                operations.add(switch (random.nextInt(6)) {
                    case 0 -> new Create(null, random.nextBoolean() ? "new" : null,
                            random.nextInt(6) == 0 ? "alpha" : "gen" + fresh++, "N", List.of());
                    case 1 -> new Duplicate(path, rev, null, "dup" + fresh++, List.of());
                    case 2 -> new SetFields(path, rev, List.of(set(id, "texture", "t" + random.nextInt(3))));
                    case 3 -> new Move(path, rev, "items/moved/m" + fresh++ + ".yml");
                    case 4 -> new Delete(path, rev);
                    default -> new SaveAsTemplate(path, rev, null, "tpl" + fresh++, List.of("material"));
                });
            }
            ApiResult<TransactionResult> dry = api.transaction(editor(),
                    new TransactionRequest(PACK, operations, true));
            assertThat(dry.value()).isNotNull();
            assertThat(dry.value().applied()).isFalse();
            assertThat(dry.value().transactionId()).isNull();
            if (dry.value().accepted()) {
                accepted++;
            } else {
                refused++;
            }
            assertThat(snapshot()).as("round " + round + ": " + operations).isEqualTo(before);
            assertThat(reloads).isEmpty();
        }
        // the generator really exercised both outcomes, or the property above proves little
        assertThat(accepted).isGreaterThan(3);
        assertThat(refused).isGreaterThan(3);
    }

    // ------------------------------------------------------------------
    // apply

    @Test
    void applyWritesTheListAtomicallyAndLeavesUntouchedDocumentsByteIdentical() throws Exception {
        String untouchedBefore = read("items/untouched.yml");
        TransactionResult dry = value(run(true, mixedList().toArray(TransactionOperation[]::new)));

        ApiResult<TransactionResult> applied = run(false, mixedList().toArray(TransactionOperation[]::new));

        assertThat(applied.ok()).as(String.valueOf(applied.diagnostics())).isTrue();
        TransactionResult result = applied.value();
        assertThat(result.applied()).isTrue();
        assertThat(result.transactionId()).startsWith("tx-");
        // what was checked is what is applied: the dry run's documents are the written ones
        assertThat(result.documents()).isEqualTo(dry.documents());

        assertThat(read("items/untouched.yml")).isEqualTo(untouchedBefore);
        assertThat(read("items/a.yml")).isEqualTo("# keep this comment\na:\n  material: STICK # inline\n  texture: gem\n");
        assertThat(read("items/b.yml")).isEqualTo("b:\n  material: STICK\n  texture: ruby\n");
        assertThat(read("items/ruby.yml")).contains("texture: ruby_final");
        assertThat(temp.resolve("packs/demo/items/c.yml")).doesNotExist();
        assertThat(read("items/moved/c.yml")).isEqualTo("c:\n  material: STICK\n");
        assertThat(temp.resolve("packs/demo/items/dd.yml")).doesNotExist();
        assertThat(read("templates/from_a.yml")).contains("material: STICK");
        assertThat(read("items/t.yml")).doesNotContain("template:").contains("material: PAPER", "Forged");

        // one journal entry per document, with the revision the report promised
        assertThat(api.getHistory(PACK, "items/b.yml", 10).value()).extracting(AuthoringApi.RevisionInfo::revision)
                .containsExactly(1, 2);
        assertThat(api.getHistory(PACK, "items/untouched.yml", 10).value()).hasSize(1);
        assertThat(api.getHistory(PACK, "items/moved/c.yml", 10).value()).extracting(AuthoringApi.RevisionInfo::action)
                .containsExactly("move-from:items/c.yml");
        assertThat(api.getHistory(PACK, "items/c.yml", 10).value()).extracting(AuthoringApi.RevisionInfo::action)
                .endsWith("delete");
        assertThat(api.getDocument(PACK, "items/ruby.yml").value().revision()).isEqualTo(1);
        // one audit entry, naming the transaction; one reload of the pack
        String audit = Files.readString(temp.resolve("audit.log"));
        assertThat(audit.lines().filter(line -> line.contains("authoring.transaction")).count()).isEqualTo(1);
        assertThat(audit).contains(result.transactionId());
        assertThat(reloads).containsExactly(PACK + "|" + PACK);
    }

    @Test
    void laterOperationsAddressDocumentsEarlierOnesCreate() throws Exception {
        ApiResult<TransactionResult> applied = run(false,
                new Create(null, null, "src", "Src", List.of()),
                new SaveAsTemplate("items/src.yml", null, null, "src_base", List.of("material")),
                new Create(null, null, "child", "Child", List.of(set("child", "template", "src_base"))),
                new DetachTemplate("items/child.yml", null, null),
                new Duplicate("items/child.yml", null, null, "grandchild", List.of(set("grandchild", "texture", "g"))),
                new Move("items/grandchild.yml", null, "items/moved/grandchild.yml"));

        assertThat(applied.ok()).as(String.valueOf(applied.diagnostics())).isTrue();
        assertThat(read("templates/src_base.yml")).contains("src_base:", "material: STICK");
        assertThat(read("items/child.yml")).doesNotContain("template:");
        // a document created and moved within the list is one created document at its final path
        assertThat(temp.resolve("packs/demo/items/grandchild.yml")).doesNotExist();
        assertThat(read("items/moved/grandchild.yml")).contains("grandchild:", "texture: g");
        assertThat(applied.value().documents()).extracting(TransactionDocument::path)
                .contains("items/src.yml", "templates/src_base.yml", "items/child.yml", "items/moved/grandchild.yml")
                .doesNotContain("items/grandchild.yml");
        assertThat(api.getHistory(PACK, "items/moved/grandchild.yml", 5).value()).extracting(
                AuthoringApi.RevisionInfo::action).containsExactly("create");
    }

    @Test
    void aDocumentCreatedAndDeletedWithinTheListIsNotWritten() throws Exception {
        List<String> before = snapshot();
        ApiResult<TransactionResult> applied = run(false,
                new Create(null, null, "ghost", "Ghost", List.of()),
                new Delete("items/ghost.yml", null));

        assertThat(applied.ok()).isTrue();
        assertThat(applied.value().documents()).isEmpty();
        assertThat(applied.value().applied()).isFalse();
        assertThat(snapshot().stream().filter(line -> line.contains("ghost"))).isEmpty();
        assertThat(snapshot()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // refusals

    @Test
    void aStaleRevisionIsMtr0034ForThatDocumentAndRefusesTheWholeList() throws Exception {
        List<String> before = snapshot();

        ApiResult<TransactionResult> result = run(false,
                new Create(null, null, "fresh", "Fresh", List.of()),
                new SetFields("items/b.yml", 7, List.of(set("b", "texture", "x"))),
                new Delete("items/dd.yml", 1));

        assertThat(result.ok()).isFalse();
        TransactionResult report = result.value();
        assertThat(report.accepted()).isFalse();
        assertThat(report.applied()).isFalse();
        assertThat(report.diagnostics()).hasSize(1);
        OperationDiagnostic found = report.diagnostics().get(0);
        assertThat(found.operation()).isEqualTo(1);
        assertThat(found.diagnostic().code().code()).isEqualTo("MTR-0034");
        assertThat(found.diagnostic().message()).contains("items/b.yml");
        // nothing from the operations that were fine
        assertThat(snapshot()).isEqualTo(before);
        assertThat(reloads).isEmpty();
    }

    @Test
    void aMissingRevisionOnAnExistingDocumentIsRefusedButACreatedOneNeedsNone() throws Exception {
        ApiResult<TransactionResult> missing = run(true,
                new SetFields("items/b.yml", null, List.of(set("b", "texture", "x"))));
        assertThat(missing.ok()).isFalse();
        assertThat(missing.value().diagnostics().get(0).diagnostic().message()).contains("expectedRevision");

        // the first operation checked it, so a second on the same document may repeat the null
        ApiResult<TransactionResult> twice = run(true,
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x"))),
                new SetFields("items/b.yml", null, List.of(set("b", "extra", "y"))));
        assertThat(twice.ok()).as(String.valueOf(twice.diagnostics())).isTrue();
        assertThat(document(twice.value(), "items/b.yml").operations()).containsExactly(0, 1);
        assertThat(document(twice.value(), "items/b.yml").revision()).isEqualTo(2);
    }

    @Test
    void aMissingPermissionIsOneDiagnosticPerOperationAndAppliesNothing() throws Exception {
        List<String> before = snapshot();

        ApiResult<TransactionResult> result = run(only("materione.editor"), false,
                new Create(null, null, "fresh", "Fresh", List.of()),
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x"))));

        assertThat(result.ok()).isFalse();
        assertThat(result.value().diagnostics()).extracting(OperationDiagnostic::operation).containsExactly(0, 1);
        assertThat(result.value().diagnostics()).allSatisfy(found -> {
            assertThat(found.diagnostic().code()).isEqualTo(DiagnosticCodes.PERMISSION_DENIED);
            assertThat(found.diagnostic().message()).contains("materione.edit." + PACK);
        });
        assertThat(snapshot()).isEqualTo(before);

        // with the node it passes, and a dry run asks the same question
        assertThat(run(only("materione.edit." + PACK), true,
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x")))).ok()).isTrue();
        assertThat(run(only("materione.editor"), true,
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x")))).ok()).isFalse();
    }

    @Test
    void aWriteToABuiltInPackIsADiagnosticPerOperationAndAppliesNothing() throws Exception {
        api.builtinPackIds(Set.of("materione"));
        List<String> before = snapshot();

        ApiResult<TransactionResult> result = api.transaction(editor(), new TransactionRequest("materione", List.of(
                new Create(null, null, "fresh", "Fresh", List.of()),
                new Delete("items/browser.yml", 1)), false));

        assertThat(result.ok()).isFalse();
        assertThat(result.value().diagnostics()).extracting(OperationDiagnostic::operation).containsExactly(0, 1);
        assertThat(result.value().diagnostics()).allSatisfy(found -> {
            assertThat(found.diagnostic().code()).isEqualTo(DiagnosticCodes.BUILTIN_PACK_ID_RESERVED);
            assertThat(found.diagnostic().message()).contains("built-in", "read-only");
        });
        assertThat(snapshot()).isEqualTo(before);
        assertThat(temp.resolve("packs/materione")).doesNotExist();
    }

    @Test
    void anUnknownPackAndAnOverlongListAreRefusedAsTheListNotAnOperation() {
        ApiResult<TransactionResult> unknown = api.transaction(editor(), new TransactionRequest("nopack", List.of(
                new Delete("items/a.yml", 1)), true));
        assertThat(unknown.ok()).isFalse();
        assertThat(unknown.value().diagnostics().get(0).operation()).isEqualTo(-1);

        List<TransactionOperation> tooMany = java.util.Collections.nCopies(TransactionRequest.MAX_OPERATIONS + 1,
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x"))));
        ApiResult<TransactionResult> overlong = api.transaction(editor(), new TransactionRequest(PACK, tooMany, true));
        assertThat(overlong.ok()).isFalse();
        assertThat(overlong.value().diagnostics().get(0).operation()).isEqualTo(-1);

        assertThat(api.transaction(editor(), new TransactionRequest(PACK, List.of(), true)).ok()).isFalse();
    }

    @Test
    void aTransactionTouchesAtMostFiftyDocuments() throws Exception {
        List<String> before = snapshot();
        List<TransactionOperation> creates = new ArrayList<>();
        for (int i = 0; i < TransactionRequest.MAX_DOCUMENTS + 1; i++) {
            creates.add(new Create(null, null, "gen_%02d".formatted(i), "G", List.of()));
        }
        ApiResult<TransactionResult> over = api.transaction(editor(), new TransactionRequest(PACK, creates, false));
        assertThat(over.ok()).isFalse();
        assertThat(over.value().diagnostics().get(0).operation()).isEqualTo(TransactionRequest.MAX_DOCUMENTS);
        assertThat(over.value().diagnostics().get(0).diagnostic().message()).contains("at most 50 documents");
        assertThat(snapshot()).isEqualTo(before);

        ApiResult<TransactionResult> exactly = api.transaction(editor(), new TransactionRequest(
                PACK, creates.subList(0, TransactionRequest.MAX_DOCUMENTS), false));
        assertThat(exactly.ok()).as(String.valueOf(exactly.diagnostics())).isTrue();
        assertThat(exactly.value().documents()).hasSize(TransactionRequest.MAX_DOCUMENTS);
    }

    @Test
    void collisionsAndPathsRemovedEarlierInTheListAreRefusedAtTheOperationThatCausesThem() throws Exception {
        TransactionResult exists = value(run(true, new Create(null, null, "alpha", "A", List.of())));
        assertThat(exists.accepted()).isFalse();
        assertThat(exists.diagnostics().get(0).diagnostic().message()).contains("items/alpha.yml");

        TransactionResult again = value(run(true,
                new Delete("items/dd.yml", 1), new Create(null, null, "dd", "D", List.of())));
        assertThat(again.accepted()).isFalse();
        assertThat(again.diagnostics().get(0).operation()).isEqualTo(1);
        assertThat(again.diagnostics().get(0).diagnostic().message()).contains("cannot be created again");

        // an id already declared in another document of the type is MTR-0056, a created one included
        TransactionResult dup = value(run(true,
                new Create(null, "one", "twin", "T", List.of()), new Create(null, "two", "twin", "T", List.of())));
        assertThat(dup.accepted()).isFalse();
        assertThat(dup.diagnostics().get(0).operation()).isEqualTo(1);
        assertThat(dup.diagnostics().get(0).diagnostic().code()).isEqualTo(DiagnosticCodes.DUPLICATE_RESOURCE_ID);

        // an id freed earlier in the same list may be declared again
        TransactionResult freed = value(run(true,
                new Delete("items/dd.yml", 1), new Create(null, "again", "dd", "D", List.of())));
        assertThat(freed.accepted()).as(String.valueOf(freed.diagnostics())).isTrue();

        TransactionResult missing = value(run(true, new Delete("items/ghost.yml", 1)));
        assertThat(missing.diagnostics().get(0).diagnostic().code()).isEqualTo(DiagnosticCodes.UNRESOLVED_REFERENCE);
    }

    // ------------------------------------------------------------------
    // fault injection

    /**
     * A failure after the k-th document is written, for every k, restores every file and journal and removes the
     * directories the transaction created: the same store, byte for byte, as before the call.
     */
    @Test
    void aFailureAfterAnyDocumentLeavesNoPartialState() throws Exception {
        TransactionOperation[] list = mixedList().toArray(TransactionOperation[]::new);
        int writes = (int) value(run(true, list)).documents().stream()
                .filter(doc -> doc.kind() != Kind.UNCHANGED).count();
        assertThat(writes).isGreaterThanOrEqualTo(9);
        List<String> before = snapshot();

        for (int k = 0; k < writes; k++) {
            int failAt = k;
            api.commitObserver((index, write) -> {
                if (index == failAt) {
                    throw new IOException("injected failure after document " + failAt + " (" + write.path() + ")");
                }
            });
            ApiResult<TransactionResult> failed = run(false, list);

            assertThat(failed.ok()).as("fault at " + k).isFalse();
            assertThat(failed.value().applied()).isFalse();
            assertThat(failed.value().diagnostics().get(0).diagnostic().message()).contains("nothing was written");
            assertThat(snapshot()).as("fault at document " + k).isEqualTo(before);
            assertThat(reloads).as("no reload after a failed apply").isEmpty();
        }

        api.commitObserver(null);
        ApiResult<TransactionResult> applied = run(false, list);
        assertThat(applied.ok()).as(String.valueOf(applied.diagnostics())).isTrue();
        assertThat(applied.value().applied()).isTrue();
    }

    // ------------------------------------------------------------------
    // validation of the result

    @Test
    void anErrorInTheResultIsAttributedToTheLastOperationOnThatDocumentAndRefusesTheApply() throws Exception {
        List<String> before = snapshot();

        ApiResult<TransactionResult> dry = run(true,
                new Create(null, null, "bad", "Bad", List.of()),
                new SetFields("items/b.yml", 1, List.of(set("b", "texture", "ok"))),
                new SetFields("items/bad.yml", null, List.of(set("bad", "texture", "missing"))));

        assertThat(dry.ok()).isFalse();
        assertThat(dry.value().accepted()).isFalse();
        OperationDiagnostic found = dry.value().diagnostics().stream()
                .filter(diagnostic -> diagnostic.diagnostic().severity() == Severity.ERROR).findFirst().orElseThrow();
        assertThat(found.operation()).isEqualTo(2);
        assertThat(found.diagnostic().message()).contains("texture is missing");
        // the resulting documents are still there to look at
        assertThat(document(dry.value(), "items/bad.yml").text()).contains("texture: missing");

        ApiResult<TransactionResult> applied = run(false,
                new Create(null, null, "bad", "Bad", List.of(set("bad", "texture", "missing"))));
        assertThat(applied.ok()).isFalse();
        assertThat(applied.value().applied()).isFalse();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aWarningIsReportedButDoesNotRefuseTheApply() throws Exception {
        ApiResult<TransactionResult> applied = run(false,
                new Create(null, null, "careful", "Careful", List.of(set("careful", "warn", true))));

        assertThat(applied.ok()).isTrue();
        assertThat(applied.value().applied()).isTrue();
        assertThat(applied.value().diagnostics()).hasSize(1);
        assertThat(applied.value().diagnostics().get(0).diagnostic().severity()).isEqualTo(Severity.WARN);
        assertThat(applied.value().diagnostics().get(0).operation()).isEqualTo(0);
    }

    @Test
    void aDocumentThatDoesNotParseAsItsTypeIsReportedByTheSchemaStage() {
        ApiResult<TransactionResult> dry = run(true, new Create(null, null, "plain", "Plain", List.of(
                FieldEdit.removal(List.of("plain", "material")))));

        assertThat(dry.value().accepted()).isFalse();
        assertThat(dry.value().diagnostics().get(0).diagnostic().code()).isEqualTo(DiagnosticCodes.CONTENT_SCHEMA_INVALID);
    }

    @Test
    void theReportSaysWhichStagesRanAndNeverClaimsTheOnesThatDidNot() {
        TransactionResult wired = value(run(true, new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x")))));
        assertThat(stage(wired, "syntax")).isTrue();
        assertThat(stage(wired, "schema")).isTrue();
        assertThat(stage(wired, "build")).isTrue();
        assertThat(stage(wired, "behavior")).as("no behavior engine in core").isFalse();
        assertThat(stage(wired, "gui")).isFalse();
        assertThat(stage(wired, "references")).isFalse();
        assertThat(stage(wired, "platform")).isFalse();

        build(false);
        TransactionResult bare = value(run(true, new SetFields("items/b.yml", 1, List.of(set("b", "texture", "x")))));
        assertThat(stage(bare, "build")).as("no allocation preview wired").isFalse();
        assertThat(stage(bare, "schema")).isTrue();
    }

    private static boolean stage(TransactionResult result, String id) {
        return result.stages().stream().filter(stage -> stage.id().equals(id)).findFirst().orElseThrow().ran();
    }

    // ------------------------------------------------------------------
    // the store, byte for byte

    /** Every file and directory of the packs and the revision journals, plus the audit log: path and content hash. */
    private List<String> snapshot() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Path root : List.of(temp.resolve("packs"), temp.resolve("revisions"))) {
            if (!Files.exists(root)) {
                lines.add("absent " + root.getFileName());
                continue;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted().forEach(path -> {
                    String relative = root.getFileName() + "/" + root.relativize(path).toString().replace('\\', '/');
                    try {
                        lines.add(Files.isDirectory(path) ? "D " + relative
                                : "F " + relative + " " + Documents.sha256(Files.readAllBytes(path)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        Path audit = temp.resolve("audit.log");
        lines.add(Files.exists(audit) ? "audit " + Documents.sha256(Files.readAllBytes(audit)) : "audit absent");
        return lines;
    }

    // ------------------------------------------------------------------
    // fixtures

    /**
     * An item type for the validation stages: it needs {@code material}, rejects the compile of a missing texture,
     * and diagnoses {@code warn: true} as a per-part warning.
     */
    private static final class StubItems implements ContentType {
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
            return new SelfDescription("Item", "", List.of(), "test");
        }

        @Override
        public Optional<Object> parse(
                NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
            Map<String, Object> merged = new java.util.LinkedHashMap<>();
            if (definition.get("template") instanceof String link) {
                Map<String, Object> base = templates.get(id.namespace() + ":" + link);
                if (base == null) {
                    base = templates.get("item:" + id.namespace() + ":" + link);
                }
                if (base == null) {
                    return Optional.empty();
                }
                merged.putAll(base);
            }
            merged.putAll(definition);
            return merged.containsKey("material") || merged.containsKey("template")
                    ? Optional.of(merged) : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Diagnostic> diagnose(Object parsedDefinition) {
            Map<String, Object> definition = (Map<String, Object>) parsedDefinition;
            return Boolean.TRUE.equals(definition.get("warn"))
                    ? List.of(Diagnostic.warn(DiagnosticCodes.CONTENT_SCHEMA_INVALID, "careful: a warning of the type"))
                    : List.of();
        }

        @Override
        @SuppressWarnings("unchecked")
        public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter,
                Allocations allocations) {
            if ("missing".equals(((Map<String, Object>) definition).get("texture"))) {
                throw new CompileRejectException(DiagnosticCodes.CONTENT_SCHEMA_INVALID,
                        "the texture is missing", "add the texture file");
            }
        }
    }

    /** A counter-free preview: nothing is allocated, nothing persists. */
    private static final class FreshAllocations implements Allocations {
        @Override
        public String allocate(NamespacedId resource, String kind) {
            return "1";
        }

        @Override
        public AllocatedRange range(NamespacedId resource, String kind, int count) {
            return new AllocatedRange(1, count);
        }

        @Override
        public Optional<String> lookup(NamespacedId resource, String kind) {
            return Optional.empty();
        }
    }
}
