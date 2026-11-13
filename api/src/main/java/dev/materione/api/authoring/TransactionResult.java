package dev.materione.api.authoring;

import dev.materione.api.diagnostics.Diagnostic;
import java.util.List;

/**
 * What {@link AuthoringApi#transaction} reports, for a dry run and for an apply alike (api.md section 3.3,
 * codemode.md section 10).
 *
 * <p>Unlike most calls the report is returned even when the list is refused, because the per-operation
 * diagnostics are the answer: {@code ApiResult.ok()} is {@link #accepted()}, and the value is present either way.
 *
 * @param accepted true when the list has no error diagnostic: a dry run would apply, an apply was written
 * @param applied true only when bytes were written (never for a dry run or a refusal)
 * @param dryRun whether the request asked for a dry run
 * @param transactionId the identifier of an applied transaction (it names the audit entry); null otherwise
 * @param documents every document the list changes, with the text and revision it would have or has now, in the
 *     order the list first touched them; empty when planning stopped at a refused operation
 * @param diagnostics every diagnostic, each with the operation that caused it
 * @param stages which validation stages ran on the result and which did not, so a clean report is never
 *     read as more than it checked
 */
public record TransactionResult(
        boolean accepted,
        boolean applied,
        boolean dryRun,
        String transactionId,
        List<TransactionDocument> documents,
        List<OperationDiagnostic> diagnostics,
        List<Stage> stages) {

    public TransactionResult {
        documents = documents == null ? List.of() : List.copyOf(documents);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    /** What the transaction does to one document. */
    public enum Kind {
        /** A new document. */
        CREATED,
        /** An existing document whose text changes. */
        CHANGED,
        /** An existing document the operations left byte-identical; it is not written and keeps its revision. */
        UNCHANGED,
        /** An existing document that is removed (including the source of a move). */
        DELETED,
        /** The target of a move: a new document that carries the source's journal lineage. */
        MOVED
    }

    /**
     * One document of the result. {@code revision} is the revision it has after the transaction (a dry run: the
     * one it would get); {@code previousRevision} is the stored one (null for a new document); {@code text} is the
     * resulting text (null for {@link Kind#DELETED}); {@code operations} lists the indexes of the operations that
     * touched it; {@code movedFrom} names the source of a {@link Kind#MOVED} document.
     */
    public record TransactionDocument(
            String path,
            Kind kind,
            Integer previousRevision,
            Integer revision,
            String text,
            String movedFrom,
            List<Integer> operations) {

        public TransactionDocument {
            operations = operations == null ? List.of() : List.copyOf(operations);
        }
    }

    /**
     * A diagnostic and the zero-based index of the operation that caused it; {@code -1} is a problem of the
     * list as a whole (too many operations, a second pack). A problem found in a document's final state is
     * attributed to the last operation that touched the document, because that is the operation after which
     * the document reached the state that failed.
     */
    public record OperationDiagnostic(int operation, Diagnostic diagnostic) {}

    /**
     * One validation stage of master section 5.4 (or the behavior compile that rides with it): its id, whether it
     * ran on this result, and a sentence saying what it covered or why it did not run.
     */
    public record Stage(String id, boolean ran, String note) {}
}
