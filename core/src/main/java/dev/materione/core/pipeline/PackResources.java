package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.diagnostics.SourceLocation;
import dev.materione.core.document.LosslessYamlDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The one rule that turns a content document into resources (master section 7.2): a file holds resources of exactly
 * one content type, named by its directory, and each top-level key whose value is a mapping is one resource. The
 * parse stage and a transaction's dry run both call it, so what the dry run validates is exactly the set of resources
 * a reload would build (D6).
 *
 * <p>One exception, for the directories in {@link PackLayout#SINGLE_RESOURCE_DIRS} (#469): a file with no keyed
 * resource but with top-level fields is one resource, named by the file's name. A file that declares no resource, or
 * that mixes the two shapes, is not dropped silently: its {@link Declared#shape()} findings say what shape it should
 * have.
 */
public final class PackResources {

    private PackResources() {}

    /**
     * The resources one document declares, the keys that could not be ids, and the findings about the file's shape.
     *
     * @param resources the resources, in key order (a flat file declares at most one)
     * @param malformedKeys the top-level keys that are not valid ids and are skipped
     * @param shape findings for a file that declares no resource, mixes the two shapes, or names its flat resource
     *     with no valid id; each names the shape the file should have (MTR-0008)
     */
    public record Declared(List<ContentResource> resources, List<String> malformedKeys, List<Diagnostic> shape) {}

    /** The content type of a content directory: {@code items} is {@code item}, {@code currencies} is {@code currency}. */
    public static String contentType(String directory) {
        return "currencies".equals(directory) ? "currency" : directory.substring(0, directory.length() - 1);
    }

    /**
     * A file name without its extension: the id a flat file declares. The same rule the scaffolds use for a new file's
     * id, so a file and the resource it holds are named alike.
     */
    public static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * The resources of {@code document}, read from {@code file} under content directory {@code directory} of
     * {@code packId}. A document that did not parse declares nothing; a key whose value is not a mapping is not a
     * resource; a key that is not a valid id is reported in {@link Declared#malformedKeys()} and skipped.
     */
    public static Declared declared(String packId, String directory, Path file, LosslessYamlDocument document) {
        List<ContentResource> resources = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        List<Diagnostic> shape = new ArrayList<>();
        if (document.parseError().isPresent()) {
            return new Declared(resources, malformed, shape);
        }
        String type = contentType(directory);
        boolean singleResource = PackLayout.SINGLE_RESOURCE_DIRS.contains(directory);
        List<String> fields = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        boolean keyed = false;
        for (Map.Entry<String, Object> entry : document.data().entrySet()) {
            if (entry.getValue() == null) {
                // `sword:` with nothing beneath it: a field if the file is flat, an empty resource if it is keyed
                fields.add(entry.getKey());
                empty.add(entry.getKey());
                continue;
            }
            if (!(entry.getValue() instanceof Map)) {
                fields.add(entry.getKey());
                continue;
            }
            keyed = true;
            try {
                resources.add(new ContentResource(NamespacedId.of(packId, entry.getKey()), type, file, document));
            } catch (IllegalArgumentException invalid) {
                malformed.add(entry.getKey());
            }
        }
        if (keyed) {
            for (String key : empty) {
                shape.add(finding(file, "declares '" + key + "' with no fields, so it is not a " + type,
                        "give '" + key + "' its fields, indented beneath it, or remove the key"));
            }
            if (singleResource && fields.size() > empty.size()) {
                shape.add(finding(file,
                        "mixes keyed " + type + " entries with top-level fields, and the fields are ignored",
                        "key every " + type + " by its id, or keep one " + type + " as top-level fields with no ids"));
            }
            return new Declared(resources, malformed, shape);
        }
        if (!singleResource || fields.isEmpty()) {
            String reason = fields.isEmpty()
                    ? "declares no " + type + ": the file has no top-level keys"
                    : "declares no " + type + ": its top-level keys are fields, and a " + type
                            + " file keys each " + type + " by its id";
            String hint = "key each " + type + " by its id, as '<id>:' with its fields indented beneath it"
                    + (singleResource ? ", or write the fields of one " + type + " at the top level" : "");
            shape.add(finding(file, reason, hint));
            return new Declared(resources, malformed, shape);
        }
        // one flat resource, named by the file: its fields are the document's top-level keys
        String id = stem(file.getFileName().toString());
        try {
            resources.add(new ContentResource(NamespacedId.of(packId, id), type, file, document.asSingleResource(id)));
        } catch (IllegalArgumentException invalid) {
            shape.add(finding(file, "declares one " + type + " named by its file, but '" + id
                    + "' is not a valid id (lowercase letters, digits, _ - . /), so the fields define no " + type,
                    "rename the file to a valid id, or key the " + type + " by its id inside the file"));
        }
        return new Declared(resources, malformed, shape);
    }

    /** A shape finding about one file, naming the file; an error under MTR-0008, the code of a schema mismatch. */
    private static Diagnostic finding(Path file, String message, String hint) {
        return new Diagnostic(DiagnosticCodes.CONTENT_SCHEMA_INVALID, Severity.ERROR,
                file.getFileName() + " " + message, null, new SourceLocation(file.toString(), 1, 1), hint);
    }

    /** A file's text; an unreadable file reads as empty, so it declares nothing (fail-soft, D6). */
    public static String readOrEmpty(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }
}
