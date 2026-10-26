package dev.materione.api.diagnostics;

import java.util.List;

/**
 * Where inside the involved resource a diagnostic points, so a surface can
 * open the exact field instead of a file and line (web.md §6). The path is
 * relative to the resource's own definition ({@code ["texture"]}).
 *
 * @param field the definition field path; never empty
 */
public record DiagnosticTarget(List<String> field) {

    public DiagnosticTarget {
        field = field == null ? List.of() : List.copyOf(field);
        if (field.isEmpty()) {
            throw new IllegalArgumentException("a diagnostic target needs a field path");
        }
    }

    public static DiagnosticTarget field(String... path) {
        return new DiagnosticTarget(List.of(path));
    }
}
