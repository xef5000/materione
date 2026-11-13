package dev.materione.core.pipeline;

import dev.materione.api.NamespacedId;
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
 */
public final class PackResources {

    private PackResources() {}

    /** The resources one document declares, and the keys that could not be ids. */
    public record Declared(List<ContentResource> resources, List<String> malformedKeys) {}

    /** The content type of a content directory: {@code items} is {@code item}, {@code currencies} is {@code currency}. */
    public static String contentType(String directory) {
        return "currencies".equals(directory) ? "currency" : directory.substring(0, directory.length() - 1);
    }

    /**
     * The resources of {@code document}, read from {@code file} under content directory {@code directory} of
     * {@code packId}. A document that did not parse declares nothing; a key whose value is not a mapping is not a
     * resource; a key that is not a valid id is reported in {@link Declared#malformedKeys()} and skipped.
     */
    public static Declared declared(String packId, String directory, Path file, LosslessYamlDocument document) {
        List<ContentResource> resources = new ArrayList<>();
        List<String> malformed = new ArrayList<>();
        if (document.parseError().isPresent()) {
            return new Declared(resources, malformed);
        }
        String type = contentType(directory);
        for (Map.Entry<String, Object> entry : document.data().entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            try {
                resources.add(new ContentResource(NamespacedId.of(packId, entry.getKey()), type, file, document));
            } catch (IllegalArgumentException invalid) {
                malformed.add(entry.getKey());
            }
        }
        return new Declared(resources, malformed);
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
