package dev.materione.core.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.TransactionOperation;
import dev.materione.api.authoring.TransactionRequest;
import dev.materione.api.authoring.TransactionResult;
import dev.materione.api.authoring.TransactionResult.Kind;
import dev.materione.api.authoring.TransactionResult.OperationDiagnostic;
import dev.materione.api.authoring.TransactionResult.Stage;
import dev.materione.api.authoring.TransactionResult.TransactionDocument;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.LogicAuthoringApi.LogicDraft;
import dev.materione.api.logic.variable.VariableDeclaration;
import dev.materione.core.document.AuditLog;
import dev.materione.core.document.DocumentConflictException;
import dev.materione.core.document.Documents;
import dev.materione.core.document.DocumentStore;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.ResourceCompiler;
import dev.materione.core.pipeline.TemplateResolver;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code AuthoringApi.transaction} (api.md section 3.3, codemode.md section 10): one ordered, mixed edit list on
 * the documents of one pack, planned in memory under the store lock, validated, and either reported (dry run) or
 * written all-or-none.
 *
 * <p>The list is applied to a <em>working set</em>: each document the list touches is read once and held as text,
 * and every operation edits that text with the same code the single-document calls use
 * ({@link DocumentStore#patched} for field edits, {@code LogicAuthoringApi.stage} and {@code GuiAuthoringApi.stage}
 * for the typed edits, the helpers {@link AuthoringApiImpl} shares for create, copy, detach and save-as-template).
 * A later operation therefore sees what an earlier one made: a document it created, copied or moved. The write is
 * {@link DocumentStore#commit}, the validation reuses the registry's own content types, the flow compiler and the GUI
 * compile, and nothing here writes a byte before every document is planned and checked.
 */
final class AuthoringTransactions {

    private final AuthoringApiImpl api;
    private final DocumentStore documents;
    private final Supplier<List<ContentType>> contentTypes;

    AuthoringTransactions(AuthoringApiImpl api, DocumentStore documents, Supplier<List<ContentType>> contentTypes) {
        this.api = api;
        this.documents = documents;
        this.contentTypes = contentTypes;
    }

    // ------------------------------------------------------------------
    // entry

    ApiResult<TransactionResult> run(ActorContext actor, TransactionRequest request) {
        boolean dryRun = request != null && request.dryRun();
        try {
            List<OperationDiagnostic> early = checkRequest(actor, request);
            if (!early.isEmpty()) {
                return refused(dryRun, List.of(), early, stages(false));
            }
            String transactionId = dryRun ? null : newTransactionId();
            Planned planned = documents.underLock(() -> planAndCommit(actor, request, transactionId));
            return finish(actor, request.pack(), planned, dryRun, null, "authoring.transaction",
                    "", auditDetails(request, transactionId));
        } catch (DocumentConflictException conflict) {
            return refused(dryRun, List.of(), List.of(new OperationDiagnostic(-1, conflict(conflict.getMessage()))),
                    stages(false));
        } catch (IOException | RuntimeException failure) {
            return failed(dryRun, failure);
        }
    }

    private static ApiResult<TransactionResult> failed(boolean dryRun, Exception failure) {
        return refused(dryRun, List.of(), List.of(new OperationDiagnostic(-1, Diagnostic.error(
                DiagnosticCodes.PIPELINE_STAGE_FAILED,
                "the transaction failed and nothing was written: "
                        + (failure.getMessage() != null ? failure.getMessage() : failure.toString())))),
                stages(false));
    }

    private static String newTransactionId() {
        return "tx-" + UUID.randomUUID();
    }

    /**
     * What happens after the lock is released for an applied transaction: the audit entry, the record that makes it
     * revertible, and the one reload, as every other authoring write. A dry run and a refusal only report.
     */
    private ApiResult<TransactionResult> finish(
            ActorContext actor, String pack, Planned planned, boolean dryRun, String revertOf, String operation,
            String targetNote, AuditLog.Details details) {
        boolean revertible = false;
        String revertNote = null;
        if (planned.applied()) {
            String actorId = AuthoringApiImpl.actorId(actor);
            api.auditTransaction(operation, actorId,
                    pack + " (" + planned.writes().size() + " documents, " + planned.transactionId() + targetNote + ")",
                    details);
            TransactionJournal journal = api.transactionJournal();
            if (journal == null) {
                revertNote = "this server keeps no transaction journal, so the transaction cannot be reverted";
            } else {
                TransactionJournal.Stored stored = journal.store(new TransactionJournal.Entry(
                        planned.transactionId(), pack, actorId, details.origin(), System.currentTimeMillis(),
                        revertOf, planned.journal()));
                revertible = stored.kept();
                revertNote = stored.note();
            }
            api.reloadPack(actor, pack);
        }
        TransactionResult result = new TransactionResult(
                planned.accepted(), planned.applied(), dryRun, planned.transactionId(), planned.documents(),
                planned.diagnostics(), planned.stages(), revertible, revertNote, revertOf);
        return new ApiResult<>(planned.accepted(), result,
                planned.diagnostics().stream().map(OperationDiagnostic::diagnostic).toList());
    }

    /** The audit context of an applied list: origin and approval (defaulted), the program bounded, and its digest. */
    private static AuditLog.Details auditDetails(TransactionRequest request, String transactionId) {
        String origin = request.origin() == null ? TransactionRequest.ORIGIN_EDITOR : request.origin();
        String approval = request.approval() == null ? TransactionRequest.APPROVAL_NOT_NEEDED : request.approval();
        String program = request.program();
        if (program == null || program.isEmpty()) {
            return new AuditLog.Details(origin, approval, transactionId, null, null, null);
        }
        int keep = Math.min(program.length(), TransactionRequest.MAX_PROGRAM_CHARS);
        if (keep < program.length() && Character.isHighSurrogate(program.charAt(keep - 1))) {
            keep--; // never cut a character in half
        }
        return new AuditLog.Details(origin, approval, transactionId, program.substring(0, keep),
                "sha256:" + Documents.sha256(program), program.length());
    }

    /** The checks that need no document: the request's shape, the pack, and each operation's permission. */
    private List<OperationDiagnostic> checkRequest(ActorContext actor, TransactionRequest request) throws IOException {
        List<OperationDiagnostic> found = new ArrayList<>();
        if (request == null || request.pack() == null || !request.pack().matches("[a-z0-9_-]{2,64}")) {
            found.add(bad(-1, "a transaction needs one pack id"));
            return found;
        }
        if (request.origin() != null && !TransactionRequest.ORIGIN_EDITOR.equals(request.origin())
                && !TransactionRequest.ORIGIN_CODE_MODE.equals(request.origin())) {
            found.add(bad(-1, "'origin' must be 'editor' or 'code-mode'"));
        }
        if (request.approval() != null && !TransactionRequest.APPROVAL_NOT_NEEDED.equals(request.approval())
                && !TransactionRequest.APPROVAL_GRANTED.equals(request.approval())) {
            found.add(bad(-1, "'approval' must be 'not_needed' or 'granted'"));
        }
        List<TransactionOperation> operations = request.operations();
        if (operations.isEmpty()) {
            found.add(bad(-1, "'operations' must be a non-empty list"));
        } else if (operations.size() > TransactionRequest.MAX_OPERATIONS) {
            found.add(bad(-1, "at most " + TransactionRequest.MAX_OPERATIONS + " operations per transaction"));
        }
        for (int i = 0; i < operations.size(); i++) {
            TransactionOperation operation = operations.get(i);
            if (operation == null) {
                found.add(bad(i, "operation " + i + " is empty"));
            } else if (editCount(operation) > TransactionRequest.MAX_EDITS_PER_OPERATION) {
                found.add(bad(i, "too many edits for one operation (at most "
                        + TransactionRequest.MAX_EDITS_PER_OPERATION + ")"));
            }
        }
        if (!found.isEmpty()) {
            return found;
        }
        return access(actor, request.pack(), operations.size());
    }

    /**
     * The per-operation permission check, as it has always been: one pack, so one node and one read-only check,
     * reported against every operation ({@code count} of them; a revert counts its documents). Also that the pack exists.
     */
    private List<OperationDiagnostic> access(ActorContext actor, String pack, int count) throws IOException {
        List<OperationDiagnostic> found = new ArrayList<>();
        String node = "materione.edit." + pack;
        boolean builtin = api.builtinPackIds().contains(pack);
        boolean allowed = actor != null && actor.hasPermission(node);
        for (int i = 0; i < count; i++) {
            if (builtin) {
                found.add(new OperationDiagnostic(i, new Diagnostic(
                        DiagnosticCodes.BUILTIN_PACK_ID_RESERVED, Severity.ERROR,
                        "pack '" + pack + "' is a built-in pack and read-only; operation " + i + " is refused",
                        null, null, "built-in packs ship inside the plugin and are never written (master section 7.3)")));
            } else if (!allowed) {
                found.add(new OperationDiagnostic(i, new Diagnostic(
                        DiagnosticCodes.PERMISSION_DENIED, Severity.ERROR,
                        "operation " + i + " needs the permission " + node, null, null,
                        "ask an operator for " + node)));
            }
        }
        if (found.isEmpty() && documents.listPacks().stream().noneMatch(info -> info.id().equals(pack))) {
            found.add(new OperationDiagnostic(-1, Diagnostic.error(
                    DiagnosticCodes.UNRESOLVED_REFERENCE, "no such pack: " + pack)));
        }
        return found;
    }

    // ------------------------------------------------------------------
    // revert (#344)

    /**
     * Reverts transaction {@code transactionId} by writing each document it touched back to its before-image, as one
     * transaction through the same stages and the same commit as any other (a dry run included). Everything is
     * checked before anything is written; a document that is no longer as the transaction left it refuses the
     * whole revert, and every such document is reported.
     */
    ApiResult<TransactionResult> revert(ActorContext actor, String transactionId, boolean dryRun) {
        try {
            TransactionJournal journal = api.transactionJournal();
            if (journal == null) {
                return refused(dryRun, List.of(), List.of(new OperationDiagnostic(-1, unavailable(
                        "this server keeps no transaction journal, so no transaction can be reverted"))), stages(false));
            }
            TransactionJournal.Loaded loaded = journal.load(transactionId);
            if (loaded.entry() == null) {
                return refused(dryRun, List.of(),
                        List.of(new OperationDiagnostic(-1, unavailable(loaded.problem()))), stages(false));
            }
            TransactionJournal.Entry entry = loaded.entry();
            List<OperationDiagnostic> early = access(actor, entry.pack(), entry.documents().size());
            if (!early.isEmpty()) {
                return refused(dryRun, List.of(), early, stages(false));
            }
            String revertId = dryRun ? null : newTransactionId();
            Planned planned = documents.underLock(() -> planRevert(actor, entry, dryRun, revertId));
            return finish(actor, entry.pack(), planned, dryRun, entry.id(), "authoring.revertTransaction",
                    ", reverts " + entry.id(),
                    AuditLog.Details.of(TransactionRequest.ORIGIN_EDITOR, TransactionRequest.APPROVAL_NOT_NEEDED,
                            revertId));
        } catch (DocumentConflictException conflict) {
            return refused(dryRun, List.of(), List.of(new OperationDiagnostic(-1, conflict(conflict.getMessage()))),
                    stages(false));
        } catch (IOException | RuntimeException failure) {
            return failed(dryRun, failure);
        }
    }

    private static Diagnostic unavailable(String message) {
        return new Diagnostic(DiagnosticCodes.TRANSACTION_REVERT_UNAVAILABLE, Severity.ERROR, message, null, null,
                "a transaction can be reverted only while its before-images are kept (codemode.md section 10); "
                        + "make the change again by hand");
    }

    /** Under the store lock: check every document is as the transaction left it, plan the restore, validate, write. */
    private Planned planRevert(ActorContext actor, TransactionJournal.Entry entry, boolean dryRun, String revertId)
            throws IOException {
        Plan plan = new Plan(entry.pack());
        List<OperationDiagnostic> diagnostics = new ArrayList<>();
        for (int i = 0; i < entry.documents().size(); i++) {
            plan.restore(i, entry, entry.documents().get(i), diagnostics);
        }
        if (!diagnostics.isEmpty()) {
            return new Planned(false, false, null, List.of(), diagnostics, stages(false), List.of(), List.of());
        }
        return validateAndCommit(actor, entry.pack(), plan, diagnostics, dryRun, revertId);
    }

    private static int editCount(TransactionOperation operation) {
        return switch (operation) {
            case TransactionOperation.Create create -> create.edits().size();
            case TransactionOperation.Duplicate duplicate -> duplicate.edits().size();
            case TransactionOperation.SetFields set -> set.edits().size();
            case TransactionOperation.EditLogic logic -> logic.edits().size();
            case TransactionOperation.EditGui gui -> gui.edits().size();
            case TransactionOperation.SaveAsTemplate template -> template.fields().size();
            case TransactionOperation.Move ignored -> 0;
            case TransactionOperation.Delete ignored -> 0;
            case TransactionOperation.DetachTemplate ignored -> 0;
        };
    }

    // ------------------------------------------------------------------
    // plan, validate, write: one hold of the store lock

    private Planned planAndCommit(ActorContext actor, TransactionRequest request, String transactionId)
            throws IOException {
        Plan plan = new Plan(request.pack());
        List<OperationDiagnostic> diagnostics = new ArrayList<>();
        List<TransactionOperation> operations = request.operations();
        boolean refused = false;
        for (int i = 0; i < operations.size(); i++) {
            try {
                plan.apply(i, operations.get(i));
            } catch (Refusal refusal) {
                for (Diagnostic diagnostic : refusal.diagnostics) {
                    diagnostics.add(new OperationDiagnostic(i, diagnostic));
                }
                refused = true;
                break; // later operations may depend on this one: they are not evaluated
            }
        }
        if (refused) {
            return new Planned(false, false, null, List.of(), diagnostics, stages(false), List.of(), List.of());
        }
        return validateAndCommit(actor, request.pack(), plan, diagnostics, request.dryRun(), transactionId);
    }

    /**
     * The tail the transaction and its revert share: the documents the plan changes, the validation stages over the
     * result, and, for an accepted apply, the all-or-none commit stamped with {@code transactionId}.
     */
    private Planned validateAndCommit(
            ActorContext actor, String pack, Plan plan, List<OperationDiagnostic> diagnostics, boolean dryRun,
            String transactionId) throws IOException {
        List<TransactionDocument> shown = new ArrayList<>();
        List<DocumentStore.PlannedWrite> writes = new ArrayList<>();
        for (Work work : plan.docs.values()) {
            if (work.isNoop() || !work.mutated) {
                continue;
            }
            shown.add(work.shown());
            DocumentStore.PlannedWrite write = work.plannedWrite();
            if (write != null) {
                writes.add(write);
            }
        }
        Validation validation = new Validation(plan).run();
        diagnostics.addAll(validation.diagnostics());
        boolean accepted = diagnostics.stream().noneMatch(found -> found.diagnostic().severity() == Severity.ERROR);
        if (dryRun || !accepted || writes.isEmpty()) {
            return new Planned(accepted, false, null, shown, diagnostics, validation.stages(), List.of(), List.of());
        }
        List<DocumentStore.CommitOutcome> outcomes = documents.commit(
                pack, writes, AuthoringApiImpl.actorId(actor), transactionId, api.commitObserver());
        Map<String, DocumentStore.CommitOutcome> byPath = new LinkedHashMap<>();
        outcomes.forEach(outcome -> byPath.put(outcome.path(), outcome));
        List<TransactionDocument> written = new ArrayList<>();
        for (TransactionDocument document : shown) {
            DocumentStore.CommitOutcome outcome = byPath.get(document.path());
            written.add(outcome == null ? document : new TransactionDocument(
                    document.path(), document.kind(), document.previousRevision(), outcome.revision(),
                    document.text(), document.movedFrom(), document.operations()));
        }
        // what a revert needs: for each written document, its state before and the state this transaction left
        List<TransactionJournal.Doc> recorded = new ArrayList<>();
        for (Work work : plan.docs.values()) {
            DocumentStore.CommitOutcome outcome = byPath.get(work.path);
            if (work.isNoop() || !work.mutated || outcome == null) {
                continue;
            }
            recorded.add(new TransactionJournal.Doc(work.path, work.kind(), work.previousRevision(),
                    outcome.revision(), outcome.sha256(), work.movedFrom, work.original));
        }
        return new Planned(true, true, transactionId, written, diagnostics, validation.stages(), writes, recorded);
    }

    /** What planning, validating and (for an apply) writing produced. */
    private record Planned(
            boolean accepted,
            boolean applied,
            String transactionId,
            List<TransactionDocument> documents,
            List<OperationDiagnostic> diagnostics,
            List<Stage> stages,
            List<DocumentStore.PlannedWrite> writes,
            List<TransactionJournal.Doc> journal) {}

    private static ApiResult<TransactionResult> refused(
            boolean dryRun, List<TransactionDocument> documents, List<OperationDiagnostic> diagnostics,
            List<Stage> stages) {
        TransactionResult result = new TransactionResult(false, false, dryRun, null, documents, diagnostics, stages);
        return new ApiResult<>(false, result, diagnostics.stream().map(OperationDiagnostic::diagnostic).toList());
    }

    // ------------------------------------------------------------------
    // the working set

    /** One document the list touches: its stored state and its state now. */
    private static final class Work {
        final String path;
        /** The stored text; null when the document does not exist (or is created by this list). */
        final String original;
        final int baseRevision;
        /**
         * A revert restoring a deleted document sets this to the revision of the delete entry: the document is
         * written back as the next revision after it, not as revision 1. Negative otherwise.
         */
        int restoreBase = -1;
        /** The journal action a revert writes ({@code revert}); null for an ordinary transaction. */
        String action;
        /** The text now; null when the document is absent (never existed, deleted, or moved away). */
        String text;
        String movedFrom;
        /** An operation has checked {@code expectedRevision} against the stored one. */
        boolean guarded;
        /** An operation changes or may change this document (a pure read, the source of a copy, does not). */
        boolean mutated;
        final List<Integer> operations = new ArrayList<>();

        Work(String path, String original, int baseRevision) {
            this.path = path;
            this.original = original;
            this.baseRevision = baseRevision;
            this.text = original;
        }

        boolean stored() {
            return original != null;
        }

        /** Never stored and not there now: created and removed within the list. */
        boolean isNoop() {
            return original == null && text == null;
        }

        /** The revision the document had before the transaction, or the delete a restore follows; null when none. */
        Integer previousRevision() {
            return stored() ? Integer.valueOf(baseRevision) : (restoreBase >= 0 ? Integer.valueOf(restoreBase) : null);
        }

        Kind kind() {
            if (original == null) {
                return movedFrom != null ? Kind.MOVED : Kind.CREATED;
            }
            if (text == null) {
                return Kind.DELETED;
            }
            return text.equals(original) ? Kind.UNCHANGED : Kind.CHANGED;
        }

        int lastOperation() {
            return operations.isEmpty() ? -1 : operations.get(operations.size() - 1);
        }

        TransactionDocument shown() {
            Kind kind = kind();
            Integer revision = switch (kind) {
                case CREATED, MOVED -> restoreBase >= 0 ? restoreBase + 1 : 1;
                case CHANGED, DELETED -> baseRevision + 1;
                case UNCHANGED -> baseRevision;
            };
            return new TransactionDocument(
                    path, kind, previousRevision(), revision, text, movedFrom, operations);
        }

        DocumentStore.PlannedWrite plannedWrite() {
            return switch (kind()) {
                case CREATED, MOVED -> restoreBase >= 0
                        ? new DocumentStore.PlannedWrite(
                                path, DocumentStore.WriteKind.RESTORE, 0, text, null, action)
                        : new DocumentStore.PlannedWrite(
                                path, DocumentStore.WriteKind.CREATE, 0, text, movedFrom);
                case CHANGED -> new DocumentStore.PlannedWrite(
                        path, DocumentStore.WriteKind.SET, baseRevision, text, null, action);
                case DELETED -> new DocumentStore.PlannedWrite(
                        path, DocumentStore.WriteKind.DELETE, baseRevision, null, null);
                case UNCHANGED -> null;
            };
        }
    }

    /** An operation cannot apply: the diagnostics that say why. Planning stops. */
    private static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient List<Diagnostic> diagnostics;

        Refusal(List<Diagnostic> diagnostics) {
            super(diagnostics.get(0).message(), null, false, false);
            this.diagnostics = diagnostics;
        }

        Refusal(Diagnostic diagnostic) {
            this(List.of(diagnostic));
        }
    }

    private static Refusal refuse(String message) {
        return new Refusal(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID, message));
    }

    private static Diagnostic conflict(String message) {
        return new Diagnostic(DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT, Severity.ERROR, message, null, null,
                "reload the affected documents and write the list again against the new state");
    }

    private static OperationDiagnostic bad(int operation, String message) {
        return new OperationDiagnostic(operation, Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID, message));
    }

    private static List<DocumentStore.FieldEdit> storeEdits(List<FieldEdit> edits) {
        List<DocumentStore.FieldEdit> out = new ArrayList<>();
        for (FieldEdit edit : edits) {
            out.add(new DocumentStore.FieldEdit(edit.field(), edit.value(), edit.remove()));
        }
        return out;
    }

    /** The planning state of one transaction, and the operations that change it. */
    private final class Plan {

        final String pack;
        final Map<String, Work> docs = new LinkedHashMap<>();

        Plan(String pack) {
            this.pack = pack;
        }

        void apply(int index, TransactionOperation operation) throws IOException {
            try {
                switch (operation) {
                    case TransactionOperation.Create create -> create(index, create);
                    case TransactionOperation.Duplicate duplicate -> duplicate(index, duplicate);
                    case TransactionOperation.SetFields set -> setFields(index, set);
                    case TransactionOperation.Move move -> move(index, move);
                    case TransactionOperation.Delete delete -> delete(index, delete);
                    case TransactionOperation.DetachTemplate detach -> detach(index, detach);
                    case TransactionOperation.SaveAsTemplate template -> saveAsTemplate(index, template);
                    case TransactionOperation.EditLogic logic -> editLogic(index, logic);
                    case TransactionOperation.EditGui gui -> editGui(index, gui);
                }
            } catch (Refusal refusal) {
                throw refusal;
            } catch (DocumentConflictException conflict) {
                throw new Refusal(conflict(conflict.getMessage()));
            } catch (NoSuchFileException absent) {
                throw new Refusal(Diagnostic.error(DiagnosticCodes.UNRESOLVED_REFERENCE,
                        "no such document: " + absent.getFile()));
            } catch (IllegalArgumentException | IllegalStateException failure) {
                throw refuse(failure.getMessage());
            }
        }

        // -- documents

        /** The document at {@code path}: held already, or read now (absent when it does not exist). */
        Work load(int index, String path) throws IOException {
            Work work = docs.get(path);
            if (work == null) {
                documents.requireDocumentPath(pack, path);
                Optional<DocumentStore.Document> stored = documents.readIfPresent(pack, path);
                work = stored.map(document -> new Work(path, document.raw(), document.revision()))
                        .orElseGet(() -> new Work(path, null, 0));
                docs.put(path, work);
            }
            return work;
        }

        /** A document the operation changes: it must exist now. Marks it as touched by {@code index}. */
        Work existing(int index, String path) throws IOException {
            Work work = load(index, path);
            if (work.text == null) {
                throw new NoSuchFileException(path);
            }
            return touched(index, work);
        }

        Work touched(int index, Work work) {
            work.mutated = true;
            if (!work.operations.contains(index)) {
                work.operations.add(index);
            }
            long counted = docs.values().stream().filter(other -> other.mutated && !other.isNoop()).count();
            if (counted > TransactionRequest.MAX_DOCUMENTS) {
                throw refuse("a transaction touches at most " + TransactionRequest.MAX_DOCUMENTS
                        + " documents (one pack, decision C12); split it into two");
            }
            return work;
        }

        /**
         * The revision check. A document that exists on disk is checked against its stored revision the first
         * time an operation names it (required), and again whenever an operation repeats it; a document the list
         * created has no revision to check.
         */
        void guard(Work work, Integer expected) {
            if (!work.stored()) {
                return;
            }
            if (expected == null) {
                if (!work.guarded) {
                    throw refuse("expectedRevision is required for the existing document " + work.path);
                }
                return;
            }
            if (expected != work.baseRevision) {
                throw new Refusal(conflict(work.path + ": document changed elsewhere: expected rev " + expected
                        + ", current rev " + work.baseRevision));
            }
            work.guarded = true;
        }

        /** A source that is only read (a copy, a template): checked when the author gave its revision. */
        void guardRead(Work work, Integer expected) {
            if (work.stored() && expected != null) {
                guard(work, expected);
            }
        }

        /** A new document's slot: the path must be free, and the id undeclared elsewhere in the type. */
        Work freeSlot(int index, String path, String type, String resourceId) throws IOException {
            Work work = load(index, path);
            if (work.text != null) {
                throw refuse("document already exists: " + path);
            }
            if (work.stored()) {
                throw refuse(path + " was deleted or moved away earlier in this transaction; "
                        + "it cannot be created again in the same one");
            }
            String declared = declaredElsewhere(type, path, resourceId);
            if (declared != null) {
                throw new Refusal(new Diagnostic(DiagnosticCodes.DUPLICATE_RESOURCE_ID, Severity.ERROR,
                        "resource " + resourceId + " is already declared in " + declared, null, null,
                        "open the existing resource, or choose a different id"));
            }
            return work;
        }

        /** The path of a document that declares {@code id} for the type, counting this list's own documents. */
        String declaredElsewhere(String type, String path, String id) {
            String onDisk = api.declaredElsewhere(pack, type, path, id);
            if (onDisk != null) {
                Work held = docs.get(onDisk);
                if (held == null || (held.text != null && declares(held.text, id))) {
                    return onDisk;
                }
            }
            for (Work other : docs.values()) {
                if (!other.path.equals(path) && other.text != null && other.path.startsWith(type + "/")
                        && declares(other.text, id)) {
                    return other.path;
                }
            }
            return null;
        }

        // -- revert

        /**
         * One document of a revert: checks it is exactly as the reverted transaction left it (the revision and the
         * hash the commit recorded; a hand edit since is read as an {@code external} revision, so it fails the check),
         * and plans the inverse. A document that moved on is reported in {@code conflicts} and nothing is planned for
         * it; the caller refuses the whole revert when there is any.
         */
        void restore(int index, TransactionJournal.Entry entry, TransactionJournal.Doc recorded,
                List<OperationDiagnostic> conflicts) throws IOException {
            String path = recorded.path();
            Optional<DocumentStore.Document> current = documents.readIfPresent(pack, path);
            boolean leftPresent = recorded.kind() != Kind.DELETED;
            String problem = null;
            if (leftPresent) {
                if (current.isEmpty()) {
                    problem = "was deleted after " + entry.id();
                } else if (current.get().revision() != recorded.postRevision()
                        || !current.get().sha256().equals(recorded.postSha256())) {
                    problem = "changed after " + entry.id() + ": it was left at rev " + recorded.postRevision()
                            + ", it is at rev " + current.get().revision();
                }
            } else if (current.isPresent()) {
                problem = "exists again after " + entry.id() + " deleted it (rev " + current.get().revision() + ")";
            } else {
                var last = documents.history(pack, path, 1);
                if (!last.isEmpty() && last.get(last.size() - 1).rev() != recorded.postRevision()) {
                    problem = "was written after " + entry.id() + " deleted it";
                }
            }
            if (problem == null && recorded.kind() != Kind.CREATED && recorded.kind() != Kind.MOVED
                    && recorded.before() == null) {
                problem = "has no before-image in the record of " + entry.id();
            }
            if (problem != null) {
                conflicts.add(new OperationDiagnostic(index, conflict(
                        path + ": document " + problem + "; the transaction cannot be reverted over it")));
                return;
            }
            Work work = docs.get(path);
            if (work != null) {
                conflicts.add(new OperationDiagnostic(index, conflict(path + ": appears twice in the record")));
                return;
            }
            if (current.isPresent()) {
                work = new Work(path, current.get().raw(), current.get().revision());
                work.guarded = true;
            } else {
                work = new Work(path, null, 0);
                work.restoreBase = recorded.postRevision();
            }
            work.action = "revert";
            // created and moved-to documents are removed; changed and deleted ones return to their before-image
            work.text = (recorded.kind() == Kind.CREATED || recorded.kind() == Kind.MOVED) ? null : recorded.before();
            docs.put(path, work);
            touched(index, work);
        }

        // -- operations

        void create(int index, TransactionOperation.Create op) throws IOException {
            String type = op.type() == null ? "items" : op.type();
            String path = AuthoringApiImpl.documentPath(type, op.folder(), op.resourceId());
            Work work = freeSlot(index, path, type, op.resourceId());
            String content = AuthoringApiImpl.newDocumentText(path, op.resourceId(), op.displayName());
            work.text = DocumentStore.patched(path, content, storeEdits(op.edits()));
            touched(index, work);
        }

        void duplicate(int index, TransactionOperation.Duplicate op) throws IOException {
            if (op.fromPath() == null) {
                throw refuse("a duplicate needs the path to copy");
            }
            Work source = load(index, op.fromPath());
            if (source.text == null) {
                throw new NoSuchFileException(op.fromPath());
            }
            guardRead(source, op.expectedRevision());
            String type = op.fromPath().contains("/") ? op.fromPath().substring(0, op.fromPath().indexOf('/')) : "";
            String path = AuthoringApiImpl.documentPath(type, op.folder(), op.resourceId());
            Work work = freeSlot(index, path, type, op.resourceId());
            String content = AuthoringApiImpl.rekeyedCopy(source.text, op.resourceId());
            work.text = DocumentStore.patched(path, content, storeEdits(op.edits()));
            touched(index, work);
        }

        void setFields(int index, TransactionOperation.SetFields op) throws IOException {
            if (op.path() == null || op.edits().isEmpty()) {
                throw refuse("a patch needs a path and edits");
            }
            Work work = existing(index, op.path());
            guard(work, op.expectedRevision());
            work.text = DocumentStore.patched(op.path(), work.text, storeEdits(op.edits()));
        }

        void move(int index, TransactionOperation.Move op) throws IOException {
            if (op.fromPath() == null || op.toPath() == null) {
                throw refuse("a move needs a source and a target path");
            }
            if (op.fromPath().equals(op.toPath())) {
                throw refuse("move source and target are the same: " + op.fromPath());
            }
            Work source = existing(index, op.fromPath());
            guard(source, op.expectedRevision());
            Work target = load(index, op.toPath());
            if (target.text != null) {
                throw refuse("target already exists: " + op.toPath());
            }
            if (target.stored()) {
                throw refuse(op.toPath() + " was deleted or moved away earlier in this transaction; "
                        + "a move cannot put a document back in the same one");
            }
            target.text = source.text;
            target.movedFrom = source.stored() ? source.path : source.movedFrom;
            source.text = null;
            source.movedFrom = null;
            touched(index, target);
        }

        void delete(int index, TransactionOperation.Delete op) throws IOException {
            if (op.path() == null) {
                throw refuse("a delete needs a path");
            }
            Work work = existing(index, op.path());
            guard(work, op.expectedRevision());
            work.text = null;
            work.movedFrom = null;
        }

        void detach(int index, TransactionOperation.DetachTemplate op) throws IOException {
            if (op.path() == null) {
                throw refuse("a detach needs a path");
            }
            Work work = existing(index, op.path());
            guard(work, op.expectedRevision());
            List<DocumentStore.FieldEdit> edits = AuthoringApiImpl.detachEdits(
                    pack, op.path(), work.text, op.resourceId(), templatePool());
            work.text = DocumentStore.patched(op.path(), work.text, edits);
        }

        void saveAsTemplate(int index, TransactionOperation.SaveAsTemplate op) throws IOException {
            String templateId = AuthoringApiImpl.sanitizedId(op.templateId());
            if (op.fromPath() == null || op.fields().isEmpty() || op.fields().size() > 256) {
                throw refuse("choose at least one field to put in the template");
            }
            Work source = load(index, op.fromPath());
            if (source.text == null) {
                throw new NoSuchFileException(op.fromPath());
            }
            guardRead(source, op.expectedRevision());
            AuthoringApiImpl.TemplateDocument template = AuthoringApiImpl.templateDocument(
                    pack, op.fromPath(), source.text, op.resourceId(), templateId, op.fields(),
                    full -> templatePool().containsKey(full));
            Work target = load(index, template.path());
            if (target.text != null) {
                throw refuse("document already exists: " + template.path());
            }
            if (target.stored()) {
                throw refuse(template.path() + " was deleted or moved away earlier in this transaction; "
                        + "it cannot be created again in the same one");
            }
            target.text = DocumentStore.patched(template.path(), template.base(), template.edits());
            touched(index, target);
        }

        void editLogic(int index, TransactionOperation.EditLogic op) throws IOException {
            Work work = existing(index, op.path());
            guard(work, op.expectedRevision());
            Map<String, Object> tree = AuthoringApiImpl.parsed(work.text);
            ApiResult<List<FieldEdit>> staged = api.logic().stage(pack, op.path(), tree, op.edits());
            applyStaged(work, staged);
        }

        void editGui(int index, TransactionOperation.EditGui op) throws IOException {
            Work work = existing(index, op.path());
            guard(work, op.expectedRevision());
            Map<String, Object> tree = AuthoringApiImpl.parsed(work.text);
            ApiResult<List<FieldEdit>> staged = api.gui().stage(pack, op.path(), tree, op.edits());
            applyStaged(work, staged);
        }

        private void applyStaged(Work work, ApiResult<List<FieldEdit>> staged) {
            if (!staged.ok()) {
                List<Diagnostic> reasons = staged.diagnostics().isEmpty()
                        ? List.of(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID, "the edit was refused"))
                        : staged.diagnostics();
                throw new Refusal(reasons);
            }
            if (!staged.value().isEmpty()) {
                work.text = DocumentStore.patched(work.path, work.text, storeEdits(staged.value()));
            }
        }

        // -- templates

        /**
         * What a {@code template:} reference resolves against: the stored pool (templates, and the items some
         * resource names, plus the ones this list names) with this list's own templates and items laid over it,
         * and the documents it deleted or moved away taken out.
         */
        Map<String, Map<String, Object>> templatePool() {
            Set<String> wanted = new LinkedHashSet<>();
            for (Work work : docs.values()) {
                if (work.text != null) {
                    for (Map<String, Object> resource : resources(work.text).values()) {
                        String link = TemplateResolver.text(resource, "template");
                        if (link != null) {
                            wanted.add(TemplateResolver.shortRef(AuthoringApiImpl.namespaceOf(pack), link));
                        }
                    }
                }
            }
            Map<String, Map<String, Object>> pool =
                    new LinkedHashMap<>(api.summaryComputer().resolutionPool(wanted).definitions());
            for (Work work : docs.values()) {
                boolean template = work.path.startsWith("templates/");
                if (!template && !AuthoringApiImpl.isItemDocument(work.path)) {
                    continue;
                }
                String prefix = template ? "" : TemplateResolver.ITEM_PREFIX;
                if (work.original != null) {
                    resources(work.original).keySet().forEach(id -> pool.remove(prefix + pack + ":" + id));
                }
                if (work.text != null) {
                    resources(work.text).forEach((id, resource) -> pool.put(prefix + pack + ":" + id, resource));
                }
            }
            return pool;
        }
    }

    /** The resources of a document's text: each root key whose value is a mapping, by key, in file order. */
    private static Map<String, Map<String, Object>> resources(String text) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        var document = LosslessYaml.parse(text);
        if (document.parseError().isPresent()) {
            return out;
        }
        for (Map.Entry<String, Object> entry : document.data().entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> mapping) {
                out.put(String.valueOf(entry.getKey()), LosslessYaml.stringKeyed(mapping));
            }
        }
        return out;
    }

    private static boolean declares(String text, String id) {
        return resources(text).containsKey(id);
    }

    // ------------------------------------------------------------------
    // validation of the result

    private static List<Stage> stages(boolean ran) {
        return stagesFor(false, false, false, false, false, ran);
    }

    private static List<Stage> stagesFor(
            boolean schema, boolean build, boolean behavior, boolean gui, boolean references, boolean ran) {
        List<Stage> out = new ArrayList<>();
        out.add(new Stage("syntax", ran,
                ran ? "every resulting document parses as lossless YAML (an edit that would break it is refused)"
                        : "the list was refused before any document was planned"));
        out.add(new Stage("schema", schema,
                schema ? "each resource through its content type's own parse and diagnose, against the stored "
                        + "templates and this list's"
                        : "not run: no content types are wired, or the list was refused"));
        out.add(new Stage("build", build,
                build ? "each resource through its content type's compile into a discarding emitter, on the "
                        + "allocation table's non-persisting preview"
                        : "not run: no allocation preview is wired, or the list was refused"));
        out.add(new Stage("behavior", behavior,
                behavior ? "the flow compiler over every rule and flow of the resulting resources"
                        : "not run: no behavior engine is wired, or the list was refused"));
        out.add(new Stage("gui", gui,
                gui ? "the GUI compile, its open: block and its rules, as gui.validate reports them"
                        : "not run: no GUI compiler is wired, or no GUI is in the result"));
        out.add(new Stage("references", references,
                references ? "the pipeline's ReferenceValidator over the pack as the list leaves it and the other "
                        + "packs as stored: every resource the list wrote, and the untouched ones that name "
                        + "something it changed (the findings the list adds)"
                        : "not run: no parsed pack set is wired, or the list was refused"));
        out.add(new Stage("platform", false,
                "not run: the platform gate (D10) needs the live server, and the pack-wide allocation runs in the "
                        + "reload after an apply (the build stage above checks each resource against a preview)"));
        return out;
    }

    /**
     * The validation stages of master section 5.4 that are reachable on an in-memory result today. Pure over the
     * working set: nothing is written, and the allocation view is the non-persisting preview.
     */
    private final class Validation {

        private final Plan plan;
        private final List<OperationDiagnostic> found = new ArrayList<>();
        private boolean schemaRan;
        private boolean buildRan;
        private boolean behaviorRan;
        private boolean guiRan;
        private boolean referencesRan;
        private List<ContentType> registeredTypes = List.of();
        private List<StagedReferences.StagedDocument> stagedDocuments = List.of();
        private StagedReferences references;
        private StagedReferences.View staging;
        private Map<String, Map<String, Object>> templates;
        private final Map<NamespacedId, Optional<VariableDeclaration>> declaredVariables = new HashMap<>();

        Validation(Plan plan) {
            this.plan = plan;
        }

        List<OperationDiagnostic> diagnostics() {
            return found;
        }

        List<Stage> stages() {
            return stagesFor(schemaRan, buildRan, behaviorRan, guiRan, referencesRan, true);
        }

        Validation run() {
            Map<String, ContentType> types = new LinkedHashMap<>();
            List<ContentType> registered = contentTypes == null ? null : contentTypes.get();
            if (registered != null) {
                registered.forEach(type -> types.put(type.directory(), type));
            }
            Supplier<Allocations> previews = api.previewAllocations();
            boolean guiWired = !(api.gui() instanceof UnavailableGuiAuthoring);
            boolean logicWired = !(api.logic() instanceof UnavailableLogicAuthoring);
            Allocations allocations = previews == null ? null : previews.get();
            Map<String, Map<String, Object>> pool = null;
            Set<String> seen = new HashSet<>();
            registeredTypes = registered == null ? List.of() : registered;
            // the one view of the pack as the list leaves it, for the logic check and the references stage alike
            stagedDocuments = stagedDocuments();
            beginStaging();
            for (Work work : plan.docs.values()) {
                if (work.isNoop() || !work.mutated || work.text == null) {
                    continue;
                }
                Kind kind = work.kind();
                if (kind == Kind.UNCHANGED) {
                    continue;
                }
                int operation = work.lastOperation();
                String directory = work.path.contains("/") ? work.path.substring(0, work.path.indexOf('/')) : "";
                boolean isGui = "guis".equals(directory) && guiWired;
                List<Diagnostic> own = new ArrayList<>();
                ContentType type = types.get(directory);
                if (type != null && !isGui) {
                    schemaRan = true;
                    if (allocations != null) {
                        buildRan = true;
                        if (pool == null) {
                            pool = plan.templatePool();
                        }
                        compileResources(work, type, pool, allocations, own);
                    }
                }
                if (isGui) {
                    guiRan = true;
                    var gui = api.gui().validate(new dev.materione.api.gui.GuiAuthoringApi.GuiValidateRequest(
                            new dev.materione.api.gui.GuiAuthoringApi.Yaml(plan.pack, work.text), List.of(),
                            staging == null ? null : this::variable));
                    own.addAll(gui.ok() ? gui.value() : gui.diagnostics());
                } else if (logicWired && hasLogic(directory)) {
                    behaviorRan = true;
                    validateLogic(work, directory, own);
                }
                for (Diagnostic diagnostic : own) {
                    String key = work.path + "|" + diagnostic.code().code() + "|" + diagnostic.message() + "|"
                            + diagnostic.target();
                    if (seen.add(key)) {
                        found.add(new OperationDiagnostic(operation, diagnostic));
                    }
                }
            }
            runReferences(seen);
            return this;
        }

        /**
         * Reference resolution over the staged set (master 5.4 level 3), after the per-document stages so that a
         * finding they already made is not reported twice.
         */
        private void runReferences(Set<String> seen) {
            if (staging == null) {
                return;
            }
            try {
                var outcome = references.check(staging, stagedDocuments);
                referencesRan = true;
                for (OperationDiagnostic diagnostic : outcome.diagnostics()) {
                    String key = "ref|" + diagnostic.operation() + "|" + diagnostic.diagnostic().code().code() + "|"
                            + diagnostic.diagnostic().message();
                    if (seen.add(key)) {
                        found.add(diagnostic);
                    }
                }
            } catch (IOException failure) {
                // the pack could not be read: the stage did not run, and the apply's own write will say why
                referencesRan = false;
            }
        }

        /** The working set as the references stage reads it: every document the list did not no-op. */
        private List<StagedReferences.StagedDocument> stagedDocuments() {
            List<StagedReferences.StagedDocument> staged = new ArrayList<>();
            for (Work work : plan.docs.values()) {
                if (work.isNoop()) {
                    continue;
                }
                boolean touched = work.mutated && work.kind() != Kind.UNCHANGED;
                staged.add(new StagedReferences.StagedDocument(
                        work.path, work.original, work.text, touched, work.lastOperation()));
            }
            return staged;
        }

        /** Reads the pack as stored and as the list leaves it, once; none when the pack set is not wired or unreadable. */
        private void beginStaging() {
            var parsed = api.parsedContent();
            if (parsed == null) {
                return;
            }
            try {
                references = new StagedReferences(
                        plan.pack, api.packDirectory(plan.pack), parsed.get(), api.referenceCache(), api.staticReferences());
                staging = references.view(stagedDocuments);
            } catch (IOException | RuntimeException failure) {
                // the pack could not be read: neither the references stage nor the staged lookups run
                staging = null;
            }
        }

        /**
         * A persistent variable by id in the pack as the list leaves it (this pack's staged documents laid over its
         * stored ones, every other pack as last parsed), parsed by the variable content type as the pipeline's
         * pre-pass does. Empty when the view is unavailable or nothing declares it.
         */
        private Optional<VariableDeclaration> variable(NamespacedId id) {
            if (staging == null) {
                return Optional.empty();
            }
            Optional<VariableDeclaration> known = declaredVariables.get(id);
            if (known != null) {
                return known;
            }
            Optional<VariableDeclaration> parsed = Optional.empty();
            Optional<ContentResource> resource = references.declared(staging, "variable", id);
            ContentType type = registeredTypes.stream().filter(t -> t.name().equals("variable")).findFirst().orElse(null);
            if (resource.isPresent() && type != null
                    && resource.get().document().data().get(id.value()) instanceof Map<?, ?> definition) {
                try {
                    if (templates == null) {
                        templates = plan.templatePool();
                    }
                    Optional<Object> made = type.parse(id, LosslessYaml.stringKeyed(definition), templates);
                    if (made.isPresent() && made.get() instanceof VariableDeclaration declaration) {
                        parsed = Optional.of(declaration);
                    }
                } catch (RuntimeException unparsable) {
                    // a variable that does not parse declares nothing, as in the pipeline's pre-pass
                }
            }
            declaredVariables.put(id, parsed);
            return parsed;
        }

        private boolean hasLogic(String directory) {
            return directory.equals("items") || directory.equals("blocks") || directory.equals("furnitures")
                    || directory.equals("guis") || directory.equals("rules");
        }

        /** Each resource of the document through the content type's own parse, diagnose and compile. */
        private void compileResources(Work work, ContentType type, Map<String, Map<String, Object>> pool,
                Allocations allocations, List<Diagnostic> out) {
            for (Map.Entry<String, Map<String, Object>> resource : resources(work.text).entrySet()) {
                NamespacedId id;
                try {
                    id = NamespacedId.of(plan.pack, resource.getKey());
                } catch (IllegalArgumentException malformed) {
                    out.add(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID,
                            "'" + resource.getKey() + "' in " + work.path + " is not a valid resource id"));
                    continue;
                }
                var emitter = new DiscardingEmitter(plan.pack, api.packDirectory(plan.pack), out);
                ResourceCompiler.compile(id, resource.getValue(), pool, type, emitter, allocations,
                        new ResourceCompiler.Sink() {
                            @Override
                            public void publish(Diagnostic diagnostic) {
                                out.add(diagnostic);
                            }

                            @Override
                            public void quarantine(NamespacedId quarantined, Diagnostic diagnostic) {
                                // the diagnostic was published just before; nothing is skipped on disk
                            }
                        });
            }
        }

        /** The flow compiler over each resource's {@code behavior:} and {@code flows:}. */
        @SuppressWarnings("unchecked")
        private void validateLogic(Work work, String directory, List<Diagnostic> out) {
            // a rule document is a behavior host with no resource of its own: the host the warning is about
            String host = "rules".equals(directory) ? "rule" : null;
            for (Map.Entry<String, Map<String, Object>> resource : resources(work.text).entrySet()) {
                // the rules' bare on: key loads as a YAML-1.1 boolean: normalized, as the editor's parsed view is
                Object behavior = LosslessYaml.stringKeyedDeep(resource.getValue().get("behavior"));
                Object flows = LosslessYaml.stringKeyedDeep(resource.getValue().get("flows"));
                if (behavior == null && flows == null) {
                    continue;
                }
                var draft = new LogicDraft(plan.pack, resource.getKey(),
                        behavior instanceof List<?> list ? (List<Object>) list : List.of(),
                        flows instanceof Map<?, ?> map ? LosslessYaml.stringKeyed(map) : Map.of(),
                        null, host, staging == null ? null : this::variable);
                var result = api.logic().validate(draft);
                out.addAll(result.ok() ? result.value() : result.diagnostics());
            }
        }
    }
}
