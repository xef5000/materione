package dev.materione.core.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The all-or-nothing file mechanics behind every atomic studio write that
 * carries binaries: the model save-back ({@link ModelTransactionService}) and
 * the field save with assets ({@link FieldsTransactionService}). Every binary is
 * validated and staged beside its target first; the staged files are then
 * promoted under one lock; any failure restores each touched file from its
 * backup. Callers own the policy (which paths may be written) and the document
 * patch, so the rollback guarantee lives in exactly one place.
 */
final class AssetStaging {

    /** Serializes atomic staged writes: no transaction backs up another's half-written file. */
    static final Object LOCK = new Object();

    /** Upper bound on the bytes one transaction stages. */
    static final int MAX_BYTES = 8 * 1024 * 1024;

    /** Upper bound on the binaries one transaction stages. */
    static final int MAX_ASSETS = 128;

    private AssetStaging() {}

    /**
     * The validated pack root for {@code pack} under {@code packsRoot}.
     *
     * @throws IllegalArgumentException for a blank, malformed or missing pack
     */
    static Path packRoot(Path packsRoot, String pack) {
        if (pack == null || pack.isBlank()) {
            throw new IllegalArgumentException("missing field 'pack'");
        }
        if (!pack.matches("[a-z0-9_-]{2,32}")) {
            throw new IllegalArgumentException("invalid pack id: " + pack);
        }
        Path normalizedRoot = packsRoot.normalize();
        Path packRoot = normalizedRoot.resolve(pack).normalize();
        if (!packRoot.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("pack escapes content root");
        }
        if (!Files.isRegularFile(packRoot.resolve("pack.yml"))) {
            throw new IllegalArgumentException("pack does not exist: " + pack);
        }
        return packRoot;
    }

    /**
     * Validates every binary and computes its target and temp file under
     * {@code packRoot}. Nothing is written here.
     *
     * @param allowed decides which normalized, pack-relative paths the caller may write
     */
    static List<StagedAsset> stage(Path packRoot, List<ModelTransaction.Asset> values,
            Predicate<String> allowed) throws IOException {
        if (values == null || values.size() > MAX_ASSETS) {
            throw new IllegalArgumentException("too many assets in one transaction");
        }
        List<StagedAsset> assets = new ArrayList<>();
        var targets = new HashSet<Path>();
        long total = 0;
        for (ModelTransaction.Asset value : values) {
            String relative = value.path() == null ? "" : value.path().replace('\\', '/');
            if (relative.isEmpty()) {
                throw new IllegalArgumentException("missing field 'path'");
            }
            if (!allowed.test(relative) || relative.contains("..")) {
                throw new IllegalArgumentException("invalid asset path: " + relative);
            }
            if (value.bytes() == null) {
                throw new IllegalArgumentException("missing bytes for asset: " + relative);
            }
            byte[] bytes = value.bytes();
            total += bytes.length;
            if (total > MAX_BYTES) {
                throw new IllegalArgumentException("transaction exceeds 8 MiB");
            }
            Path target = packRoot.resolve(relative).normalize();
            if (!target.startsWith(packRoot)) {
                throw new IllegalArgumentException("asset escapes its pack");
            }
            if (!targets.add(target)) {
                throw new IllegalArgumentException("duplicate asset path: " + relative);
            }
            if (Files.exists(target) && !Files.isRegularFile(target)) {
                throw new IllegalArgumentException("asset target is not a regular file: " + relative);
            }
            rejectSymlinkPath(packRoot, target);
            assets.add(new StagedAsset(target, tempFor(target), bytes));
        }
        return assets;
    }

    /** The sibling temp file a staged write lands in before its atomic move. */
    static Path tempFor(Path target) {
        return target.resolveSibling(target.getFileName() + ".staged-tmp-" + System.nanoTime());
    }

    /** Refuses a target whose path crosses a symbolic link inside the pack. */
    static void rejectSymlinkPath(Path packRoot, Path target) {
        for (Path path = target; path != null && path.startsWith(packRoot); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("asset path crosses a symbolic link");
            }
            if (path.equals(packRoot)) {
                return;
            }
        }
    }

    /** Promotes a staged file over its target, atomically when the filesystem allows it. */
    static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Puts every backed-up file back as it was: rewritten when it existed, deleted when it did not. */
    static void restore(Map<Path, Backup> backups) throws IOException {
        for (var entry : backups.entrySet()) {
            if (entry.getValue().existed()) {
                Files.write(entry.getKey(), entry.getValue().bytes());
            } else {
                Files.deleteIfExists(entry.getKey());
            }
        }
    }

    /** One binary whose bytes are staged in {@code temp} and will be promoted to {@code target}. */
    record StagedAsset(Path target, Path temp, byte[] bytes) {}

    /** A file's content before the transaction touched it. */
    record Backup(boolean existed, byte[] bytes) {
        static Backup capture(Path path) throws IOException {
            return Files.isRegularFile(path)
                    ? new Backup(true, Files.readAllBytes(path))
                    : new Backup(false, new byte[0]);
        }
    }
}
