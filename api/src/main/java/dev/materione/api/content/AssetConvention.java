package dev.materione.api.content;

import java.util.List;

/**
 * How a field names an asset of a pack (the vanilla convention): by its path relative to the directory of its
 * kind under {@code assets/}, without the file extension. {@code assets/textures/item/ruby.png} is written
 * {@code item/ruby}; {@code assets/models/crate.json} is written {@code crate}.
 *
 * <p>One definition: the pipeline's asset index builds its ids with it, and the editor's self-description
 * publishes it, so a consumer that turns an uploaded file's path into a field value (the code-mode SDK) does not
 * keep the rule by hand (issue 355, benchmark S14).
 */
public final class AssetConvention {

    /** The directories under {@code assets/} whose files are referenced relative to them. */
    public static final List<String> KIND_DIRECTORIES = List.of("textures", "models", "fonts", "sounds");

    private AssetConvention() {}

    /**
     * The reference a field holds for the file at {@code path} (relative to the pack, with or without the
     * leading {@code assets/}): the kind directory and the extension removed.
     */
    public static String reference(String path) {
        String relative = path.replace('\\', '/');
        if (relative.startsWith("assets/")) {
            relative = relative.substring("assets/".length());
        }
        int slash = relative.indexOf('/');
        if (slash > 0 && KIND_DIRECTORIES.contains(relative.substring(0, slash))) {
            relative = relative.substring(slash + 1);
        }
        int dot = relative.lastIndexOf('.');
        return dot > 0 ? relative.substring(0, dot) : relative;
    }
}
