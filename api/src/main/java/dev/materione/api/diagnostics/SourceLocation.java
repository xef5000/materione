package dev.materione.api.diagnostics;

import java.util.Objects;

/**
 * Source coordinates of a diagnostic, captured by the parser's source map.
 * Nullable in a {@link Diagnostic} — runtime diagnostics have no file.
 */
public record SourceLocation(String file, int line, int column) {

    public SourceLocation {
        Objects.requireNonNull(file, "file");
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("line/column must be >= 0");
        }
    }

    @Override
    public String toString() {
        return file + ":" + line + (column > 0 ? ":" + column : "");
    }
}
