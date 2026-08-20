package dev.materione.core.pipeline;

import java.util.List;

/**
 * The on-disk pack layout (master §7.2). One place names the content-type
 * directories so discovery, validation and (later) importers agree.
 */
public final class PackLayout {

    /** Content-type directories under a pack root, in discovery order. */
    public static final List<String> CONTENT_DIRS = List.of(
            "templates", "items", "recipes", "blocks", "furniture", "glyphs", "menus", "dialogs", "huds");

    /** The manifest file at a pack root. */
    public static final String MANIFEST_FILE = "pack.yml";

    private PackLayout() {}
}
