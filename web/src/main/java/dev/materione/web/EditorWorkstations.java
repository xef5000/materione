package dev.materione.web;

import com.google.gson.JsonObject;
import dev.materione.web.protocol.Wire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trusted-workstation store (web.md §5.2): editor signing public keys this
 * server has been told to recognize, skipping the repeated in-game trust
 * prompt. Opt-in per browser (the editor persists its key in IndexedDB and
 * the admin approves "trust this browser"); revocable in-game via
 * {@code /materione editor revoke <prefix>}. Persisted as JSON under
 * {@code storage/editor-workstations.json} — hashes only, never key
 * material beyond the public keys themselves.
 */
public final class EditorWorkstations {

    /** One trusted browser: hash prefix handle, the label it said, when added. */
    public record Entry(String keyHash, String label, long addedAt) {}

    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public EditorWorkstations(Path file) {
        this.file = file;
        load();
    }

    /** sha256-hex of the raw Ed25519 public key — the stored handle. */
    public static String keyHash(byte[] editorSigningPubKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(editorSigningPubKey);
            return HexFormatHolder.FORMAT.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    public synchronized boolean isTrusted(byte[] editorSigningPubKey) {
        return entries.containsKey(keyHash(editorSigningPubKey));
    }

    public synchronized void trust(byte[] editorSigningPubKey, String label) {
        String hash = keyHash(editorSigningPubKey);
        entries.put(hash, new Entry(hash, label == null ? "" : label, System.currentTimeMillis()));
        save();
    }

    /** Revokes by key-hash prefix (what the in-game command shows). */
    public synchronized boolean revoke(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        boolean removed = entries.keySet().removeIf(hash -> hash.startsWith(prefix.toLowerCase()));
        if (removed) {
            save();
        }
        return removed;
    }

    /** Revokes every trusted workstation. */
    public synchronized void revokeAll() {
        entries.clear();
        save();
    }

    public synchronized List<Entry> list() {
        return new ArrayList<>(entries.values());
    }

    // ------------------------------------------------------------------

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = Wire.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (var element : root.getAsJsonArray("workstations")) {
                JsonObject entry = element.getAsJsonObject();
                String hash = Wire.requiredString(entry, "keyHash");
                entries.put(hash, new Entry(
                        hash,
                        entry.has("label") ? entry.get("label").getAsString() : "",
                        entry.has("addedAt") ? entry.get("addedAt").getAsLong() : 0L));
            }
        } catch (IOException | RuntimeException e) {
            // fail-soft: an unreadable store just means prompts come back
            System.getLogger(EditorWorkstations.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "editor workstation store unreadable ({0}) — treating as empty",
                    e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            var array = new com.google.gson.JsonArray();
            for (Entry entry : entries.values()) {
                JsonObject out = new JsonObject();
                out.addProperty("keyHash", entry.keyHash());
                out.addProperty("label", entry.label());
                out.addProperty("addedAt", entry.addedAt());
                array.add(out);
            }
            root.add("workstations", array);
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.getLogger(EditorWorkstations.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "editor workstation store unwritable ({0}) — trust not persisted",
                    e.getMessage());
        }
    }

    private static final class HexFormatHolder {
        static final java.util.HexFormat FORMAT = java.util.HexFormat.of();
    }
}
