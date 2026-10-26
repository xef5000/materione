package dev.materione.core.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Watches a packs root for external edits (hand-edited YAML, git pulls) and
 * feeds them to the host as {@link Change} events — the host journals the
 * drift via {@link DocumentStore#syncExternal} and pushes it to connected
 * editors, so open tabs flag the change instead of silently clobbering on
 * the next save (web.md §4.2 "document.changed").
 *
 * <p>Editor saves pass through the same store, so their hash matches the
 * journal and produce no event here. Pure JDK — the loop never spawns a
 * thread of its own (R5: only the scheduling gateway creates threads); the
 * host runs {@link #runLoop()} on an async gateway task, where it blocks
 * until {@link #close()}. File-watcher events are debounced to a quiet
 * period (plugin.md §2, ~500 ms).
 */
public final class PackWatcher implements AutoCloseable {

    /** One debounced change: a document path relative to the packs root. */
    public record Change(String pack, String path, boolean deleted) {}

    private static final long QUIET_MS = 500;

    private final Path packsRoot;
    private final DocumentStore documents;
    private final Consumer<Change> sink;
    private final Runnable directoryCreated;
    private volatile boolean running = true;
    private WatchService watchService;

    public PackWatcher(Path packsRoot, DocumentStore documents, Consumer<Change> sink)
            throws IOException {
        this(packsRoot, documents, sink, null);
    }

    public PackWatcher(
            Path packsRoot, DocumentStore documents, Consumer<Change> sink, Runnable directoryCreated)
            throws IOException {
        this.packsRoot = packsRoot;
        this.documents = documents;
        this.sink = Objects.requireNonNull(sink, "sink");
        this.directoryCreated = directoryCreated == null ? () -> { } : directoryCreated;
        this.watchService = packsRoot.getFileSystem().newWatchService();
        registerAll(packsRoot);
    }

    /**
     * Drains watch events until {@link #close()} — call on a background
     * thread the host owns (the scheduling gateway's async executor).
     */
    public void runLoop() {
        Map<Path, Long> pending = new LinkedHashMap<>();
        try {
            while (running) {
                WatchService service = watchService;
                if (service == null) {
                    return; // closed
                }
                WatchKey key = service.poll(100, TimeUnit.MILLISECONDS);
                if (key != null) {
                    Path dir = (Path) key.watchable();
                    long now = System.nanoTime();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        Path changed = dir.resolve((Path) event.context());
                        if (Files.isDirectory(changed, LinkOption.NOFOLLOW_LINKS)) {
                            // A moved-in subtree may already contain files and nested directories.
                            try {
                                registerAll(changed, file -> pending.put(file, now));
                            } catch (IOException e) {
                                // fail-soft: an unwatchable subdir loses only its own events
                            }
                            directoryCreated.run();
                            continue;
                        }
                        pending.put(changed, now);
                    }
                    key.reset();
                }
                if (!pending.isEmpty()) {
                    long latest = 0;
                    for (long seen : pending.values()) {
                        latest = Math.max(latest, seen);
                    }
                    long quietForMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - latest);
                    if (quietForMs >= QUIET_MS) {
                        flush(pending);
                    }
                }
            }
        } catch (InterruptedException e) {
            // shutdown via close(); the interrupt flag survives for the host
        }
    }

    @Override
    public void close() {
        running = false;
        WatchService service = watchService;
        if (service != null) {
            try {
                service.close(); // unblocks poll(); runLoop exits on !running
            } catch (IOException e) {
                // closing is best-effort (D6)
            }
            watchService = null;
        }
    }

    private void registerAll(Path root) throws IOException {
        registerAll(root, file -> { });
    }

    private void registerAll(Path root, Consumer<Path> existingFile) throws IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(path)) continue;
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    path.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    existingFile.accept(path);
                }
            }
        }
    }

    /** Maps pending paths onto documents and journals/pushes each drifted one. */
    private void flush(Map<Path, Long> pending) {
        pending.keySet().removeIf(path -> {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
            String name = path.getFileName().toString();
            // DocumentStore's atomic writes land via a temp sibling first
            return name.contains(".tmp-");
        });
        for (Path file : pending.keySet()) {
            Path relative;
            try {
                relative = packsRoot.relativize(file);
            } catch (IllegalArgumentException e) {
                continue; // outside the packs root
            }
            if (relative.getNameCount() < 2) {
                continue; // packs-root level noise, not a pack file
            }
            String pack = relative.getName(0).toString();
            String path = relative.subpath(1, relative.getNameCount()).toString().replace('\\', '/');
            try {
                DocumentStore.ExternalSync sync = documents.syncExternal(pack, path);
                if (sync.changed()) {
                    sink.accept(new Change(pack, path, sync.revision() < 0));
                }
            } catch (IOException | RuntimeException e) {
                // fail-soft: non-document paths (assets/) and unreadable files
                // are simply not pushed; the watcher itself keeps running
            }
        }
        pending.clear();
    }
}
