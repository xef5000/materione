package dev.materione.api.authoring;

import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.gui.GuiAuthoringApi;
import dev.materione.api.logic.LogicEdit;
import java.util.List;

/**
 * One operation of a {@link TransactionRequest} (api.md section 3.3, codemode.md section 10). The
 * list is applied in order to an in-memory copy of the pack's documents, so an operation may address
 * a document an earlier one created, duplicated or moved.
 *
 * <p>Paths are pack-relative ({@code items/ruby.yml}). {@code expectedRevision} is the revision the
 * author read: it is required the first time an operation names a document that already exists, and
 * it is the <em>stored</em> revision whichever operations came before (the author never saw an
 * in-transaction one). For a document the list itself creates it is ignored, and null is the normal
 * value. A stale revision is {@code MTR-0034} and refuses the whole list.
 *
 * <p>Every edit vocabulary is the one the single-document calls use: {@link FieldEdit} for
 * {@code setFields}, {@link LogicEdit} for {@code logic().edit}, {@link GuiAuthoringApi.GuiEdit}
 * for {@code gui().edit}. Nothing here is a second implementation of an edit.
 */
public sealed interface TransactionOperation {

    /**
     * A new resource of {@code type} (default {@code items}) with the type's scaffold, then
     * {@code edits}, as {@code bulk}'s {@code CREATE}. {@code folder} is a subdirectory of the type.
     */
    record Create(String type, String folder, String resourceId, String displayName, List<FieldEdit> edits)
            implements TransactionOperation {
        public Create {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /**
     * A lossless copy of {@code fromPath} re-keyed to {@code resourceId}, then {@code edits}. The source may
     * be a document an earlier operation created. {@code expectedRevision} guards a stored source and is
     * optional: when given, what was copied is what was reviewed.
     */
    record Duplicate(
            String fromPath, Integer expectedRevision, String folder, String resourceId, List<FieldEdit> edits)
            implements TransactionOperation {
        public Duplicate {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /** Lossless field patches to one document, as {@code setFields}. */
    record SetFields(String path, Integer expectedRevision, List<FieldEdit> edits)
            implements TransactionOperation {
        public SetFields {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /** Moves a document to {@code toPath}, as {@code moveDocument} (the target's journal records where it came from). */
    record Move(String fromPath, Integer expectedRevision, String toPath) implements TransactionOperation {}

    /** Deletes a document, as {@code deleteDocument}; the journal keeps the history. */
    record Delete(String path, Integer expectedRevision) implements TransactionOperation {}

    /**
     * Materialises a resource's inherited values and drops its {@code template:} link, as
     * {@code detachTemplate}. The chain resolves against the stored templates and the ones this list creates.
     */
    record DetachTemplate(String path, Integer expectedRevision, String resourceId)
            implements TransactionOperation {}

    /**
     * Writes {@code templates/<templateId>.yml} with the chosen authored fields of {@code resourceId} in
     * {@code fromPath}, as {@code saveAsTemplate}. The source is untouched; {@code expectedRevision} guards a
     * stored source and is optional.
     */
    record SaveAsTemplate(
            String fromPath, Integer expectedRevision, String resourceId, String templateId, List<String> fields)
            implements TransactionOperation {
        public SaveAsTemplate {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    /** Structural edits to the {@code behavior:} and {@code flows:} of one resource, as {@code logic().edit}. */
    record EditLogic(String path, Integer expectedRevision, List<LogicEdit> edits)
            implements TransactionOperation {
        public EditLogic {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }

    /**
     * Structural edits to one GUI, as {@code gui().edit}. A transaction carries no binaries, so the paint and
     * image layers that need a PNG are written through {@code gui().edit} itself.
     */
    record EditGui(String path, Integer expectedRevision, List<GuiAuthoringApi.GuiEdit> edits)
            implements TransactionOperation {
        public EditGui {
            edits = edits == null ? List.of() : List.copyOf(edits);
        }
    }
}
