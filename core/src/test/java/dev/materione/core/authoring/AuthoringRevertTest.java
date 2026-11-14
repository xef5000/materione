package dev.materione.core.authoring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.TransactionOperation;
import dev.materione.api.authoring.TransactionOperation.Create;
import dev.materione.api.authoring.TransactionOperation.Delete;
import dev.materione.api.authoring.TransactionOperation.DetachTemplate;
import dev.materione.api.authoring.TransactionOperation.Duplicate;
import dev.materione.api.authoring.TransactionOperation.Move;
import dev.materione.api.authoring.TransactionOperation.SaveAsTemplate;
import dev.materione.api.authoring.TransactionOperation.SetFields;
import dev.materione.api.authoring.TransactionRequest;
import dev.materione.api.authoring.TransactionResult;
import dev.materione.api.authoring.TransactionResult.Kind;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.Documents;
import dev.materione.core.document.ModelTransactionService;
import dev.materione.core.pipeline.ParsedContent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reverting a transaction (codemode.md section 10, issue 344): the before-images a transaction leaves put every
 * document back as a <em>new revision</em>, through the same stages and the same commit as any other transaction,
 * and refuse, with nothing written, when a document is no longer as the transaction left it.
 */
class AuthoringRevertTest {

    private static final String PACK = "demo";

    @TempDir
    Path temp;

    private DocumentStore store;
    private AuthoringApiImpl api;
    private AuditLog audit;
    private final List<String> reloads = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Path packsRoot = temp.resolve("packs");
        store = new DocumentStore(packsRoot, temp.resolve("revisions"));
        audit = new AuditLog(temp.resolve("audit.log"));
        api = new AuthoringApiImpl(store, audit, packsRoot,
                new ModelTransactionService(store, packsRoot, audit), null, () -> null,
                (actor, pack, file) -> reloads.add(pack + "|" + file.getFileName()),
                null, List::of);
        api.transactionJournal(temp.resolve("transactions"));
        api.createPack(editor(), PACK).value();
        seed("items/a.yml", "# keep this comment\na:\n  material: STICK # inline\n  texture: gem\n");
        seed("items/b.yml", "b:\n  material: STICK\n  texture: gem\n");
        seed("items/c.yml", "c:\n  material: STICK\n");
        seed("items/dd.yml", "dd:\n  material: STICK\r\n  texture: \"caf\u00e9\"\n");
        seed("items/untouched.yml", "untouched:\n  # never edited\n  material: BONE\n\n  texture:   spaced\n");
        seed("templates/base.yml", "base:\n  material: PAPER\n  lore:\n    - Forged\n");
        seed("items/t.yml", "t:\n  template: base\n  display_name: T\n");
        reloads.clear();
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

    private ApiResult<TransactionResult> run(boolean dryRun, TransactionOperation... operations) {
        return api.transaction(editor(), new TransactionRequest(PACK, List.of(operations), dryRun));
    }

    private static FieldEdit set(String root, String field, Object value) {
        return new FieldEdit(List.of(root, field), value);
    }

    private int rev(String path) throws IOException {
        return store.read(PACK, path).revision();
    }

    /** The ten operations of the benchmark's shape: create, copy, patch, move, delete, save and detach a template. */
    private TransactionOperation[] mixedList() throws IOException {
        return new TransactionOperation[] {
            new Create(null, null, "ruby", "Ruby", List.of(set("ruby", "texture", "ruby"))),
            new Duplicate("items/a.yml", rev("items/a.yml"), null, "a_copy", List.of(set("a_copy", "texture", "copy"))),
            new SetFields("items/b.yml", rev("items/b.yml"), List.of(set("b", "texture", "ruby"))),
            new SetFields("items/ruby.yml", null, List.of(set("ruby", "texture", "ruby_final"))),
            new Move("items/c.yml", rev("items/c.yml"), "items/moved/c.yml"),
            new Delete("items/dd.yml", rev("items/dd.yml")),
            new SaveAsTemplate("items/a.yml", rev("items/a.yml"), null, "from_a", List.of("material")),
            new DetachTemplate("items/t.yml", rev("items/t.yml"), null),
            new Create(null, "gear", "belt", "Belt", List.of()),
            new SetFields("items/untouched.yml", rev("items/untouched.yml"),
                    List.of(set("untouched", "material", "BONE")))
        };
    }

    private TransactionResult applied(ApiResult<TransactionResult> result) {
        assertThat(result.value()).as(String.valueOf(result.diagnostics())).isNotNull();
        assertThat(result.value().applied()).as(String.valueOf(result.diagnostics())).isTrue();
        return result.value();
    }

    // ------------------------------------------------------------------
    // the journal knows its transaction

    @Test
    void everyJournalEntryATransactionWritesCarriesItsIdAndOlderEntriesStillRead() throws Exception {
        TransactionResult done = applied(run(false, mixedList()));

        List<String> written = new ArrayList<>();
        for (TransactionResult.TransactionDocument document : done.documents()) {
            if (document.kind() == Kind.UNCHANGED) {
                continue; // named by the list, left byte-identical: not written, no entry
            }
            written.add(document.path());
            DocumentStore.Revision last = lastRevision(document.path());
            assertThat(last.transaction()).as(document.path()).isEqualTo(done.transactionId());
            assertThat(last.rev()).isEqualTo(document.revision());
        }
        assertThat(written).hasSize(9);
        // a document the list left alone was written by no transaction; so were the seeds
        assertThat(store.history(PACK, "items/untouched.yml", 10)).hasSize(1);
        assertThat(store.history(PACK, "items/a.yml", 10).get(0).transaction()).isNull();
        assertThat(store.history(PACK, "items/b.yml", 10).get(0).transaction()).isNull();
        // the documents of one transaction can be found again by its id, from the journals alone
        List<String> found = new ArrayList<>();
        for (String path : written) {
            if (store.history(PACK, path, 10).stream()
                    .anyMatch(entry -> done.transactionId().equals(entry.transaction()))) {
                found.add(path);
            }
        }
        assertThat(found).containsExactlyInAnyOrderElementsOf(written);
    }

    private DocumentStore.Revision lastRevision(String path) throws IOException {
        List<DocumentStore.Revision> history = store.history(PACK, path, 50);
        return history.get(history.size() - 1);
    }

    // ------------------------------------------------------------------
    // revert restores bytes

    @Test
    void aRevertPutsTheStoreBackByteForByteWithTwoMoreRevisionsPerDocument() throws Exception {
        List<String> before = packFiles();
        TransactionOperation[] list = mixedList();
        TransactionResult done = applied(run(false, list));
        assertThat(done.revertible()).as(String.valueOf(done.revertNote())).isTrue();
        assertThat(packFiles()).isNotEqualTo(before);
        reloads.clear();

        ApiResult<TransactionResult> reverted = api.revertTransaction(editor(), done.transactionId());

        TransactionResult report = applied(reverted);
        assertThat(report.revertOf()).isEqualTo(done.transactionId());
        assertThat(report.transactionId()).startsWith("tx-").isNotEqualTo(done.transactionId());
        assertThat(packFiles()).as("every document is back, byte for byte").isEqualTo(before);
        assertThat(reloads).as("one reload for the revert").hasSize(1);
        // created documents are gone, the deleted one is back, the moved one is back where it was
        assertThat(temp.resolve("packs/demo/items/ruby.yml")).doesNotExist();
        assertThat(temp.resolve("packs/demo/items/moved/c.yml")).doesNotExist();
        assertThat(temp.resolve("packs/demo/items/dd.yml")).exists();
        assertThat(temp.resolve("packs/demo/items/c.yml")).exists();
        // each touched document gained exactly two revisions: the transaction's and the revert's, as new ones
        for (TransactionResult.TransactionDocument document : done.documents()) {
            if (document.kind() == Kind.UNCHANGED) {
                continue;
            }
            List<DocumentStore.Revision> history = store.history(PACK, document.path(), 50);
            DocumentStore.Revision last = history.get(history.size() - 1);
            assertThat(last.transaction()).as(document.path()).isEqualTo(report.transactionId());
            assertThat(last.rev()).as(document.path()).isEqualTo(document.revision() + 1);
            // revisions only grow, whatever the revert did to the file
            for (int i = 1; i < history.size(); i++) {
                assertThat(history.get(i).rev()).isGreaterThan(history.get(i - 1).rev());
            }
        }
        assertThat(lastRevision("items/b.yml").action()).isEqualTo("revert");
        assertThat(lastRevision("items/dd.yml").action()).isEqualTo("revert");
        assertThat(rev("items/b.yml")).isEqualTo(3);
        assertThat(rev("items/dd.yml")).isEqualTo(3); // seed 1, delete 2, restore 3

        // the report names the kinds: created ones are deleted, the deleted one is restored
        assertThat(kind(report, "items/ruby.yml")).isEqualTo(Kind.DELETED);
        assertThat(kind(report, "items/dd.yml")).isEqualTo(Kind.CREATED);
        assertThat(kind(report, "items/b.yml")).isEqualTo(Kind.CHANGED);
        assertThat(report.stages().stream().filter(stage -> stage.id().equals("syntax")).findFirst().orElseThrow().ran())
                .isTrue();
    }

    private static Kind kind(TransactionResult result, String path) {
        return result.documents().stream().filter(doc -> doc.path().equals(path)).findFirst().orElseThrow().kind();
    }

    @Test
    void theRevertIsItselfRevertible() throws Exception {
        List<String> before = packFiles();
        TransactionResult done = applied(run(false, mixedList()));
        List<String> after = packFiles();
        TransactionResult reverted = applied(api.revertTransaction(editor(), done.transactionId()));
        assertThat(reverted.revertible()).isTrue();

        applied(api.revertTransaction(editor(), reverted.transactionId()));

        assertThat(packFiles()).as("reverting the revert is the transaction again").isEqualTo(after);
        assertThat(after).isNotEqualTo(before);
    }

    @Test
    void aRevertDryRunReportsWhatItWouldRestoreAndWritesNothing() throws Exception {
        TransactionResult done = applied(run(false, mixedList()));
        reloads.clear();
        List<String> before = snapshot();

        ApiResult<TransactionResult> dry = api.revertTransaction(editor(), done.transactionId(), true);

        TransactionResult report = dry.value();
        assertThat(dry.ok()).as(String.valueOf(dry.diagnostics())).isTrue();
        assertThat(report.dryRun()).isTrue();
        assertThat(report.applied()).isFalse();
        assertThat(report.transactionId()).isNull();
        assertThat(report.revertOf()).isEqualTo(done.transactionId());
        assertThat(report.documents()).hasSize((int) done.documents().stream().filter(doc -> doc.kind() != Kind.UNCHANGED).count());
        assertThat(report.documents().stream().filter(doc -> doc.path().equals("items/dd.yml")).findFirst()
                .orElseThrow().text()).contains("caf\u00e9");
        assertThat(snapshot()).as("a dry run writes nothing, not even a record").isEqualTo(before);
        assertThat(reloads).isEmpty();
        // and the same call as an apply still works afterwards
        applied(api.revertTransaction(editor(), done.transactionId()));
    }

    // ------------------------------------------------------------------
    // refusals

    @Test
    void aRevertAfterALaterHumanEditRefusesAndNothingIsWritten() throws Exception {
        TransactionResult done = applied(run(false, mixedList()));
        // a human edits a document the transaction changed, by hand, outside the editor
        Path b = temp.resolve("packs/demo/items/b.yml");
        Files.writeString(b, Files.readString(b) + "  display_name: Hand edited\n");
        reloads.clear();
        // reading the hand-edited document baselines it in its journal (as every read does), once
        api.revertTransaction(editor(), done.transactionId(), true);
        List<String> before = snapshot();

        for (boolean dryRun : new boolean[] {true, false}) {
            ApiResult<TransactionResult> refused = api.revertTransaction(editor(), done.transactionId(), dryRun);

            assertThat(refused.ok()).isFalse();
            TransactionResult report = refused.value();
            assertThat(report.accepted()).isFalse();
            assertThat(report.applied()).isFalse();
            assertThat(report.documents()).isEmpty();
            assertThat(report.diagnostics()).hasSize(1);
            OperationDiagnostic found = report.diagnostics().get(0);
            assertThat(found.diagnostic().code()).isEqualTo(DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT);
            assertThat(found.diagnostic().message()).contains("items/b.yml", done.transactionId());
            assertThat(snapshot()).as("refused (dryRun=" + dryRun + "), so nothing written").isEqualTo(before);
        }
        assertThat(reloads).isEmpty();
        assertThat(Files.readString(b)).endsWith("Hand edited\n");
    }

    @Test
    void aLaterEditThroughTheEditorRefusesToo_andEveryDocumentThatMovedOnIsReported() throws Exception {
        TransactionResult done = applied(run(false, mixedList()));
        // a created document edited, a deleted one written again, a changed one deleted
        var later = api.setFields(editor(), new AuthoringApi.SetFieldsRequest(PACK, "items/ruby.yml", rev("items/ruby.yml"),
                List.of(set("ruby", "texture", "later")), List.of()));
        assertThat(later.value()).as(String.valueOf(later.diagnostics())).isNotNull();
        Files.writeString(temp.resolve("packs/demo/items/dd.yml"), "dd:\n  material: BONE\n"); // by hand again
        api.deleteDocument(editor(), new AuthoringApi.DeleteDocumentRequest(PACK, "items/b.yml", rev("items/b.yml")));
        api.revertTransaction(editor(), done.transactionId(), true); // a read baselines the hand-written file, once
        List<String> before = snapshot();

        ApiResult<TransactionResult> refused = api.revertTransaction(editor(), done.transactionId());

        assertThat(refused.value().applied()).isFalse();
        List<String> messages = refused.value().diagnostics().stream()
                .map(found -> found.diagnostic().message()).toList();
        assertThat(messages).hasSize(3);
        assertThat(messages).anyMatch(message -> message.contains("items/ruby.yml") && message.contains("changed after"));
        assertThat(messages).anyMatch(message -> message.contains("items/dd.yml") && message.contains("exists again"));
        assertThat(messages).anyMatch(message -> message.contains("items/b.yml") && message.contains("was deleted after"));
        assertThat(refused.value().diagnostics()).allMatch(
                found -> found.diagnostic().code() == DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT);
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void aRevertThatWouldBreakAReferenceIsRefusedByTheSameStagesAsAnyTransaction() throws Exception {
        api.parsedContent(() -> ParsedContent.EMPTY);
        TransactionResult done = applied(run(false,
                new Create(null, null, "gem2", "Gem", List.of())));
        // a later change (by anyone) now depends on what the transaction created
        seed("recipes/forge.yml", "forge:\n  type: shapeless\n  result: gem2\n");
        List<String> before = snapshot();

        ApiResult<TransactionResult> refused = api.revertTransaction(editor(), done.transactionId());

        assertThat(refused.value().applied()).isFalse();
        assertThat(refused.value().accepted()).isFalse();
        assertThat(refused.value().diagnostics()).anyMatch(found ->
                found.diagnostic().code() == DiagnosticCodes.UNRESOLVED_REFERENCE
                        && found.diagnostic().message().contains("forge"));
        assertThat(refused.value().stages().stream().filter(stage -> stage.id().equals("references")).findFirst()
                .orElseThrow().ran()).isTrue();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void anUnknownMalformedOrUnjournaledTransactionIsMtr0104AndNothingIsWritten() throws Exception {
        List<String> before = snapshot();
        for (String id : new String[] {"tx-00000000-0000-0000-0000-000000000000", "nonsense", "../../audit", ""}) {
            ApiResult<TransactionResult> refused = api.revertTransaction(editor(), id);

            assertThat(refused.ok()).as(id).isFalse();
            assertThat(refused.value().diagnostics()).hasSize(1);
            assertThat(refused.value().diagnostics().get(0).diagnostic().code())
                    .isEqualTo(DiagnosticCodes.TRANSACTION_REVERT_UNAVAILABLE);
        }
        assertThat(DiagnosticCodes.TRANSACTION_REVERT_UNAVAILABLE.code()).isEqualTo("MTR-0104");

        api.transactionJournal((TransactionJournal) null);
        TransactionResult done = applied(run(false, new SetFields("items/b.yml", rev("items/b.yml"),
                List.of(set("b", "texture", "x")))));
        assertThat(done.revertible()).isFalse();
        assertThat(done.revertNote()).contains("no transaction journal");
        assertThat(api.revertTransaction(editor(), done.transactionId()).value().diagnostics().get(0).diagnostic().code())
                .isEqualTo(DiagnosticCodes.TRANSACTION_REVERT_UNAVAILABLE);
        assertThat(before).isNotEmpty();
    }

    @Test
    void theExistingPerOperationPermissionCheckAppliesToARevert() throws Exception {
        TransactionResult done = applied(run(false, new SetFields("items/b.yml", rev("items/b.yml"),
                List.of(set("b", "texture", "x"))), new Create(null, null, "gem3", "G", List.of())));
        List<String> before = snapshot();

        ApiResult<TransactionResult> refused = api.revertTransaction(only("materione.edit.other"), done.transactionId());

        assertThat(refused.value().applied()).isFalse();
        assertThat(refused.value().diagnostics()).hasSize(2);
        assertThat(refused.value().diagnostics()).allMatch(
                found -> found.diagnostic().code() == DiagnosticCodes.PERMISSION_DENIED);
        assertThat(snapshot()).isEqualTo(before);

        applied(api.revertTransaction(only("materione.edit." + PACK), done.transactionId()));
    }

    // ------------------------------------------------------------------
    // all-or-none, fault-injected at each document

    @Test
    void aFailureAfterAnyDocumentOfARevertLeavesNoPartialState() throws Exception {
        List<String> original = packFiles();
        TransactionResult done = applied(run(false, mixedList()));
        int writes = (int) done.documents().stream().filter(doc -> doc.kind() != Kind.UNCHANGED).count();
        assertThat(writes).isGreaterThanOrEqualTo(9);
        reloads.clear();
        List<String> before = snapshot();

        for (int k = 0; k < writes; k++) {
            int failAt = k;
            api.commitObserver((index, write) -> {
                if (index == failAt) {
                    throw new IOException("injected failure after document " + failAt + " (" + write.path() + ")");
                }
            });
            ApiResult<TransactionResult> failed = api.revertTransaction(editor(), done.transactionId());

            assertThat(failed.ok()).as("fault at " + k).isFalse();
            assertThat(failed.value().applied()).isFalse();
            assertThat(failed.value().diagnostics().get(0).diagnostic().message()).contains("nothing was written");
            assertThat(snapshot()).as("fault at document " + k).isEqualTo(before);
            assertThat(reloads).as("no reload after a failed revert").isEmpty();
        }

        api.commitObserver(null);
        applied(api.revertTransaction(editor(), done.transactionId()));
        assertThat(packFiles()).isEqualTo(original);
    }

    // ------------------------------------------------------------------
    // the property: apply then revert is the identity on the store

    @Test
    void applyThenRevertLeavesEveryDocumentByteIdenticalWhateverTheList() throws Exception {
        Random random = new Random(344);
        String[] stored = {"items/a.yml", "items/b.yml", "items/c.yml", "items/dd.yml", "templates/base.yml"};
        int applied = 0;
        int refused = 0;
        int fresh = 0;
        for (int round = 0; round < 60; round++) {
            List<TransactionOperation> operations = new ArrayList<>();
            int length = 1 + random.nextInt(5);
            for (int i = 0; i < length; i++) {
                String path = stored[random.nextInt(stored.length)];
                String id = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
                Integer revision = rev(path);
                operations.add(switch (random.nextInt(6)) {
                    case 0 -> new Create(null, random.nextBoolean() ? "new" : null, "gen" + fresh++, "N", List.of());
                    case 1 -> new Duplicate(path, revision, null, "dup" + fresh++, List.of());
                    case 2 -> new SetFields(path, revision, List.of(set(id, "texture", "t" + random.nextInt(3))));
                    case 3 -> new Move(path, revision, "items/moved/m" + fresh++ + ".yml");
                    case 4 -> new Delete(path, revision);
                    default -> new SaveAsTemplate(path, revision, null, "tpl" + fresh++, List.of("material"));
                });
            }
            List<String> before = packFiles();
            ApiResult<TransactionResult> result =
                    api.transaction(editor(), new TransactionRequest(PACK, operations, false));
            if (!result.value().applied()) {
                refused++;
                assertThat(packFiles()).isEqualTo(before);
                continue;
            }
            applied++;
            assertThat(result.value().revertible()).as(String.valueOf(result.value().revertNote())).isTrue();
            List<String> after = packFiles();

            TransactionResult reverted = applied(api.revertTransaction(editor(), result.value().transactionId()));

            assertThat(packFiles()).as("round " + round + ": " + operations).isEqualTo(before);
            // and the revert can be undone too, which is the transaction's own result again
            if (random.nextInt(4) == 0) {
                applied(api.revertTransaction(editor(), reverted.transactionId()));
                assertThat(packFiles()).as("round " + round + " redone").isEqualTo(after);
                applied(api.revertTransaction(editor(), lastTransactionId()));
                assertThat(packFiles()).isEqualTo(before);
            }
        }
        assertThat(applied).isGreaterThan(10);
        assertThat(refused).isGreaterThanOrEqualTo(0);
    }

    /** The transaction id of the newest audit entry that names one. */
    private String lastTransactionId() {
        List<AuditLog.Entry> entries = audit.recent(1000);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).transaction() != null) {
                return entries.get(i).transaction();
            }
        }
        throw new AssertionError("no transaction in the audit");
    }

    // ------------------------------------------------------------------
    // retention

    @Test
    void theRetentionCapsAreEnforcedAndAnUnrevertibleTransactionSaysSo() throws Exception {
        // at most two records, and 600 bytes for one
        api.transactionJournal(new TransactionJournal(temp.resolve("small"), 2, 1_000_000, 600));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TransactionResult done = applied(run(false, new SetFields("items/b.yml", rev("items/b.yml"),
                    List.of(set("b", "texture", "v" + i)))));
            assertThat(done.revertible()).isTrue();
            ids.add(done.transactionId());
        }
        assertThat(api.transactionJournal().count()).isEqualTo(2);
        ApiResult<TransactionResult> pruned = api.revertTransaction(editor(), ids.get(0));
        assertThat(pruned.value().diagnostics().get(0).diagnostic().code())
                .isEqualTo(DiagnosticCodes.TRANSACTION_REVERT_UNAVAILABLE);
        assertThat(pruned.value().diagnostics().get(0).diagnostic().message()).contains("retention", "2");

        // a transaction whose before-images are over the per-record cap is applied, and says it cannot be reverted
        String big = "big:\n  material: STICK\n  lore: \"" + "x".repeat(2000) + "\"\n";
        seed("items/big.yml", big);
        TransactionResult huge = applied(run(false, new SetFields("items/big.yml", rev("items/big.yml"),
                List.of(set("big", "texture", "t")))));
        assertThat(huge.revertible()).isFalse();
        assertThat(huge.revertNote()).contains("cannot be reverted", "limit");
        assertThat(Files.readString(temp.resolve("packs/demo/items/big.yml"))).contains("texture: t");
        assertThat(api.revertTransaction(editor(), huge.transactionId()).value().diagnostics().get(0).diagnostic().code())
                .isEqualTo(DiagnosticCodes.TRANSACTION_REVERT_UNAVAILABLE);

        // the newest record is still good
        applied(api.revertTransaction(editor(), ids.get(2)));
    }

    @Test
    void theTotalSizeCapPrunesTheOldestFirst() throws Exception {
        TransactionJournal journal = new TransactionJournal(temp.resolve("sized"), 100, 2_500, 2_000);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String id = "tx-00000000-0000-0000-0000-00000000000" + i;
            ids.add(id);
            var stored = journal.store(new TransactionJournal.Entry(id, PACK, "a", "editor", i, null,
                    List.of(new TransactionJournal.Doc("items/x.yml", Kind.CHANGED, 1, 2, "h", null,
                            "x:\n  y: \"" + "z".repeat(900) + "\"\n"))));
            assertThat(stored.kept()).isTrue();
        }
        assertThat(journal.count()).isLessThan(5).isGreaterThanOrEqualTo(1);
        assertThat(journal.load(ids.get(4)).entry()).isNotNull();
        assertThat(journal.load(ids.get(0)).entry()).isNull();
    }

    @Test
    void aRecordSurvivesTheDiskExactly() {
        TransactionJournal journal = new TransactionJournal(temp.resolve("exact"));
        String awkward = "a: \"quote\" \\ back\r\n\ttab \u0001 \u00e9 \u4e2d \ud83d\ude00\nlast";
        String id = "tx-12345678-1234-1234-1234-123456789abc";
        var entry = new TransactionJournal.Entry(id, PACK, "who \"me\"", "code-mode", 5, "tx-00000000-0000-0000-0000-000000000000",
                List.of(new TransactionJournal.Doc("items/a b.yml", Kind.DELETED, 3, 4, null, null, awkward),
                        new TransactionJournal.Doc("items/n.yml", Kind.MOVED, null, 1, "abc", "items/o.yml", null)));
        assertThat(journal.store(entry).kept()).isTrue();

        var loaded = journal.load(id);

        assertThat(loaded.problem()).isNull();
        assertThat(loaded.entry()).isEqualTo(entry);
    }

    // ------------------------------------------------------------------
    // the audit

    @Test
    void theAuditEntryNamesTheOriginApprovalTransactionAndTheProgramWithItsDigest() throws Exception {
        String program = "export default async (m) => {\n  m.create(\"item\", {id: 'ruby'}); // \"quoted\"\n}\n";
        TransactionResult done = applied(api.transaction(editor(), new TransactionRequest(PACK,
                List.of(new Create(null, null, "ruby", "Ruby", List.of())), false,
                TransactionRequest.ORIGIN_CODE_MODE, TransactionRequest.APPROVAL_GRANTED, program)));
        TransactionResult reverted = applied(api.revertTransaction(editor(), done.transactionId()));

        List<AuditLog.Entry> entries = audit.recent(100);
        AuditLog.Entry applyEntry = entries.stream()
                .filter(entry -> done.transactionId().equals(entry.transaction())).findFirst().orElseThrow();
        assertThat(applyEntry.op()).isEqualTo("authoring.transaction");
        assertThat(applyEntry.origin()).isEqualTo("code-mode");
        assertThat(applyEntry.approval()).isEqualTo("granted");
        assertThat(applyEntry.program()).isEqualTo(program);
        assertThat(applyEntry.programLength()).isEqualTo(program.length());
        assertThat(applyEntry.digest()).isEqualTo("sha256:" + Documents.sha256(program));
        assertThat(applyEntry.actor()).isEqualTo("editor:session-1");
        assertThat(applyEntry.ts()).isNotBlank();

        AuditLog.Entry revertEntry = entries.stream()
                .filter(entry -> reverted.transactionId().equals(entry.transaction())).findFirst().orElseThrow();
        assertThat(revertEntry.op()).isEqualTo("authoring.revertTransaction");
        assertThat(revertEntry.origin()).isEqualTo("editor");
        assertThat(revertEntry.approval()).isEqualTo("not_needed");
        assertThat(revertEntry.target()).contains(done.transactionId(), "reverts");
        assertThat(revertEntry.program()).isNull();
    }

    @Test
    void anOversizedProgramIsTruncatedWithItsDigestIntactAndNeverCutInHalf() throws Exception {
        String lines = "const x = \"é\";\n".repeat(2000);
        String emojiAtTheCut = "a".repeat(TransactionRequest.MAX_PROGRAM_CHARS - 1) + "\ud83d\ude00" + "tail";
        for (String program : new String[] {lines, emojiAtTheCut}) {
            TransactionResult done = applied(api.transaction(editor(), new TransactionRequest(PACK,
                    List.of(new Create(null, null, "p" + Math.abs(program.hashCode() % 1000), "P", List.of())), false,
                    TransactionRequest.ORIGIN_CODE_MODE, TransactionRequest.APPROVAL_NOT_NEEDED, program)));

            AuditLog.Entry entry = audit.recent(100).stream()
                    .filter(candidate -> done.transactionId().equals(candidate.transaction())).findFirst().orElseThrow();

            assertThat(entry.program().length()).isLessThanOrEqualTo(TransactionRequest.MAX_PROGRAM_CHARS);
            assertThat(program).startsWith(entry.program());
            assertThat(entry.programLength()).isEqualTo(program.length());
            assertThat(entry.digest()).as("the digest covers the whole text").isEqualTo("sha256:" + Documents.sha256(program));
            assertThat(Character.isHighSurrogate(entry.program().charAt(entry.program().length() - 1))).isFalse();
        }
        // one audit entry is still one line
        assertThat(Files.readAllLines(temp.resolve("audit.log")).stream().filter(line -> line.contains("authoring.transaction")))
                .allMatch(line -> line.startsWith("{") && line.endsWith("}"));
    }

    @Test
    void oldAuditEntriesReadAsBeforeAndABadOriginIsRefused() throws Exception {
        Files.writeString(temp.resolve("audit.log"),
                "{\"ts\":\"2026-01-01T00:00:00Z\",\"actor\":\"old\",\"op\":\"authoring.setFields\",\"target\":\"demo/items/a.yml\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        AuditLog.Entry old = audit.recent(5).stream().filter(entry -> entry.actor().equals("old")).findFirst().orElseThrow();
        assertThat(old.origin()).isEqualTo("editor");
        assertThat(old.approval()).isNull();
        assertThat(old.transaction()).isNull();
        assertThat(old.program()).isNull();
        assertThat(old.digest()).isNull();
        assertThat(new AuditLog.Entry("t", "a", "op", "x")).isEqualTo(
                new AuditLog.Entry("t", "a", "op", "x", "editor", null, null, null, null, null));

        List<String> before = snapshot();
        for (TransactionRequest bad : List.of(
                new TransactionRequest(PACK, List.of(new Create(null, null, "q", "Q", List.of())), false,
                        "hacker", null, null),
                new TransactionRequest(PACK, List.of(new Create(null, null, "q", "Q", List.of())), false,
                        null, "maybe", null))) {
            ApiResult<TransactionResult> refused = api.transaction(editor(), bad);
            assertThat(refused.value().applied()).isFalse();
            assertThat(refused.value().diagnostics().get(0).diagnostic().message()).containsAnyOf("origin", "approval");
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // the store, byte for byte

    /** Every file under the packs (path and content hash); directories are organisational and not compared. */
    private List<String> packFiles() throws IOException {
        List<String> lines = new ArrayList<>();
        Path root = temp.resolve("packs");
        try (var walk = Files.walk(root)) {
            walk.sorted().filter(Files::isRegularFile).forEach(path -> {
                try {
                    lines.add("F " + root.relativize(path).toString().replace('\\', '/') + " "
                            + Documents.sha256(Files.readAllBytes(path)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        return lines;
    }

    /** Everything a failed or refused call must leave alone: packs, journals, records, audit. */
    private List<String> snapshot() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String name : List.of("packs", "revisions", "transactions", "small")) {
            Path root = temp.resolve(name);
            if (!Files.exists(root)) {
                lines.add("absent " + name);
                continue;
            }
            try (var walk = Files.walk(root)) {
                walk.sorted().forEach(path -> {
                    String relative = name + "/" + root.relativize(path).toString().replace('\\', '/');
                    try {
                        lines.add(Files.isDirectory(path) ? "D " + relative
                                : "F " + relative + " " + Documents.sha256(Files.readAllBytes(path)));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
        Path auditFile = temp.resolve("audit.log");
        lines.add(Files.exists(auditFile) ? "audit " + Documents.sha256(Files.readAllBytes(auditFile)) : "audit absent");
        return lines;
    }
}
