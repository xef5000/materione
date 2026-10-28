package dev.materione.core.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * The on-disk pack layout (master §7.2). One place names the content-type
 * directories and the discovery rule, so the pipeline, the editor's browser,
 * the file watcher and the bootstrap reader agree on which files are content
 * (plugin architecture §4.2).
 *
 * <p>Discovery rule: a content document is a {@code .yml} file at any depth
 * under a content directory. Dot-directories ({@code .materione/}) are never
 * content, and symbolic-link directories are never entered. Resource identity
 * is the file's top-level keys, not its path, so a nested file is not special.
 */
public final class PackLayout {

    /** Content-type directories under a pack root, in discovery order. */
    public static final List<String> CONTENT_DIRS = List.of(
            "templates", "items", "recipes", "blocks", "furnitures", "glyphs", "sounds", "paintings",
            "effects", "menus", "dialogs", "huds");

    /** The manifest file at a pack root. */
    public static final String MANIFEST_FILE = "pack.yml";

    private PackLayout() {}

    /**
     * Whether a directory entry belongs to the content tree. Dot-directories
     * (editor metadata such as {@code .materione/}) are excluded at every depth.
     */
    public static boolean isDiscoveredDirectory(String name) {
        return !name.startsWith(".");
    }

    /** Whether a file name is a content document: a {@code .yml} file. */
    public static boolean isContentDocument(String fileName) {
        return fileName.endsWith(".yml");
    }

    /**
     * Whether a pack-relative path ({@code items/weapons/sword.yml}) is a
     * content document under the discovery rule. The manifest is not one.
     */
    public static boolean isContentDocumentPath(String packRelativePath) {
        String[] segments = packRelativePath.split("/");
        if (segments.length < 2 || !CONTENT_DIRS.contains(segments[0])) {
            return false;
        }
        for (int i = 1; i < segments.length - 1; i++) {
            if (!isDiscoveredDirectory(segments[i])) {
                return false;
            }
        }
        return isContentDocument(segments[segments.length - 1]);
    }

    /**
     * Every content document under one content directory, recursively, sorted by
     * pack-relative path (forward slashes, natural string order) so discovery is
     * deterministic on every platform. A missing content directory yields nothing.
     * A subtree that cannot be listed is reported to {@code unreadable} and skipped;
     * its siblings still count.
     */
    public static List<Path> contentDocuments(Path packDir, String contentDir, Consumer<IOException> unreadable) {
        Path dir = packDir.resolve(contentDir);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        var found = new ArrayList<Found>();
        collect(dir, contentDir + "/", found, unreadable);
        found.sort(Comparator.comparing(Found::relative));
        return found.stream().map(Found::file).toList();
    }

    /**
     * Every file of one pack the pipeline reads for content: its manifest (when
     * present), then the content documents of each content directory in
     * {@link #CONTENT_DIRS} order. This is the set fingerprints and scope
     * expansion are computed over.
     */
    public static List<Path> sourceDocuments(Path packDir, Consumer<IOException> unreadable) {
        var out = new ArrayList<Path>();
        Path manifest = packDir.resolve(MANIFEST_FILE);
        if (Files.isRegularFile(manifest)) {
            out.add(manifest);
        }
        for (String dir : CONTENT_DIRS) {
            out.addAll(contentDocuments(packDir, dir, unreadable));
        }
        return out;
    }

    private record Found(String relative, Path file) {}

    private static void collect(Path dir, String prefix, List<Found> out, Consumer<IOException> unreadable) {
        List<Path> children;
        try (var stream = Files.list(dir)) {
            children = stream.toList();
        } catch (IOException e) {
            unreadable.accept(e);
            return;
        }
        for (Path child : children) {
            String name = child.getFileName().toString();
            if (Files.isDirectory(child)) {
                // a symbolic link to a directory is never entered (no cycles, no escapes)
                if (!Files.isSymbolicLink(child) && isDiscoveredDirectory(name)) {
                    collect(child, prefix + name + "/", out, unreadable);
                }
            } else if (isContentDocument(name) && Files.isRegularFile(child)) {
                out.add(new Found(prefix + name, child));
            }
        }
    }
}
