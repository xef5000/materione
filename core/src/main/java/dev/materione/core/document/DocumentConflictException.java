package dev.materione.core.document;

/**
 * A save raced an external change: the document moved to revision
 * {@code currentRevision} after the caller based its edit on
 * {@code expectedRevision}. Last-writer-wins <em>with a visible warning</em>
 * (master §9.4) — the editor is expected to re-read and offer a merge, never
 * to silently clobber.
 */
public final class DocumentConflictException extends RuntimeException {

    private final int expectedRevision;
    private final int currentRevision;

    public DocumentConflictException(String message, int expectedRevision, int currentRevision) {
        super(message);
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public int expectedRevision() {
        return expectedRevision;
    }

    /** What the caller should re-read against before retrying. */
    public int currentRevision() {
        return currentRevision;
    }
}
