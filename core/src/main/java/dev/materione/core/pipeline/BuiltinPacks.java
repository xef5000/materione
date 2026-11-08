package dev.materione.core.pipeline;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The built-in packs: content the plugin ships inside its own jar, loaded through the same pipeline as any pack
 * (master section 7.3, owner decision on #284). A built-in pack is read-only by construction. It is never extracted to
 * {@code plugins/Materione/}, so no file on the server's disk can be edited to change it, and its documents are read
 * straight from the jar's file system with the same {@link PackLayout} discovery rule as a pack on disk. The files in
 * the jar are still files (D1): versioned with the plugin, nothing else is a second source of truth.
 *
 * <p>Which packs a jar ships is listed in one classpath resource, {@value #INDEX}: one pack id per line, blank lines and
 * {@code #} comments ignored. Each pack lives at {@code builtin-packs/<id>/} on the classpath and has a
 * {@code pack.yml} like any pack. The loader knows no pack by name.
 *
 * <p>Fail-soft (D6): a pack that cannot be read is reported ({@code MTR-0096}) and left out, and the rest load. A holder
 * of opened packs must be {@link #close() closed} when the plugin stops, because a jar's file system stays open for as
 * long as the packs read from it are in use.
 */
public final class BuiltinPacks implements AutoCloseable {

    /** The classpath resource that lists the pack ids a jar ships. */
    public static final String INDEX = "builtin-packs/index.txt";

    /** The classpath folder holding the packs, one subfolder per id. */
    public static final String ROOT = "builtin-packs";

    /**
     * One built-in pack.
     *
     * @param id the pack's id, which is its namespace
     * @param directory the pack root; a path in the jar's file system, or in a directory on the classpath
     */
    public record Pack(String id, Path directory) {}

    private static final BuiltinPacks NONE = new BuiltinPacks(List.of(), List.of());

    private final List<Pack> packs;
    private final List<FileSystem> owned;
    private final Set<String> ids;

    private BuiltinPacks(List<Pack> packs, List<FileSystem> owned) {
        this.packs = List.copyOf(packs);
        this.owned = List.copyOf(owned);
        var names = new LinkedHashSet<String>();
        packs.forEach(pack -> names.add(pack.id()));
        this.ids = Set.copyOf(names);
    }

    /** No built-in packs: the state of a test or a host that ships none. */
    public static BuiltinPacks none() {
        return NONE;
    }

    /** Built-in packs already at known directories (tests); nothing is opened, so nothing needs closing. */
    public static BuiltinPacks of(List<Pack> packs) {
        return new BuiltinPacks(packs, List.of());
    }

    /**
     * Opens the packs a classpath ships. A missing index is "no built-in packs" and is not an error: a build that ships
     * none is valid. Anything else that cannot be read is reported to {@code problems} and skipped.
     *
     * @param loader the class loader the plugin's resources come from
     * @param problems where a pack that could not be read is reported
     */
    public static BuiltinPacks fromClasspath(ClassLoader loader, Consumer<Diagnostic> problems) {
        URL index = loader.getResource(INDEX);
        if (index == null) {
            return NONE;
        }
        List<String> wanted;
        try {
            // no shared jar cache: reading the index must not leave the jar held open by the JDK
            var connection = index.openConnection();
            connection.setUseCaches(false);
            byte[] bytes;
            try (var in = connection.getInputStream()) {
                bytes = in.readAllBytes();
            }
            wanted = new String(bytes, StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .distinct()
                    .toList();
        } catch (IOException e) {
            problems.accept(unavailable("the index " + INDEX + " could not be read: " + e.getMessage()));
            return NONE;
        }
        var packs = new ArrayList<Pack>();
        var owned = new ArrayList<FileSystem>();
        for (String id : wanted) {
            try {
                packs.add(open(loader, id, owned));
            } catch (IOException | URISyntaxException | RuntimeException e) {
                problems.accept(unavailable("pack '" + id + "' could not be read: " + e.getMessage()));
            }
        }
        return new BuiltinPacks(packs, owned);
    }

    private static Pack open(ClassLoader loader, String id, List<FileSystem> owned)
            throws IOException, URISyntaxException {
        if (!id.matches("[a-z0-9_-]+")) {
            throw new IOException("not a pack id (lower-case letters, digits, - and _)");
        }
        URL manifest = loader.getResource(ROOT + "/" + id + "/" + PackLayout.MANIFEST_FILE);
        if (manifest == null) {
            throw new IOException("it has no " + PackLayout.MANIFEST_FILE + " under " + ROOT + "/" + id);
        }
        URI uri = manifest.toURI();
        Path manifestPath;
        if ("jar".equals(uri.getScheme())) {
            String text = uri.toString();
            int bang = text.indexOf("!/");
            URI jar = URI.create(text.substring(0, bang));
            FileSystem system;
            try {
                system = FileSystems.newFileSystem(jar, Map.of());
                owned.add(system);
            } catch (FileSystemAlreadyExistsException shared) {
                // another holder opened this jar; it is theirs to close
                system = FileSystems.getFileSystem(jar);
            }
            manifestPath = system.getPath(text.substring(bang + 1));
        } else {
            manifestPath = Path.of(uri);
        }
        return new Pack(id, manifestPath.getParent());
    }

    private static Diagnostic unavailable(String detail) {
        return new Diagnostic(DiagnosticCodes.BUILTIN_PACK_UNAVAILABLE, Severity.ERROR,
                "built-in pack unavailable: " + detail, null, null,
                "reinstall the plugin jar; the server runs without this pack until then");
    }

    /** The packs, in index order. */
    public List<Pack> packs() {
        return packs;
    }

    /** The ids of the packs; a folder in {@code content/packs} with one of these names is ignored (MTR-0095). */
    public Set<String> ids() {
        return ids;
    }

    /** Whether {@code packId} names a built-in pack. */
    public boolean isBuiltin(String packId) {
        return ids.contains(packId);
    }

    /** Whether {@code file} is read from a built-in pack. */
    public boolean contains(Path file) {
        for (Pack pack : packs) {
            if (file.getFileSystem().equals(pack.directory().getFileSystem())
                    && file.toAbsolutePath().normalize().startsWith(pack.directory().toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    /** Closes the jar file systems this holder opened. Safe to call twice. */
    @Override
    public void close() {
        for (FileSystem system : owned) {
            try {
                system.close();
            } catch (IOException | RuntimeException ignored) {
                // closing a jar that is already gone loses nothing
            }
        }
    }

    /** The ids, for logs. */
    @Override
    public String toString() {
        return "BuiltinPacks" + ids;
    }
}
