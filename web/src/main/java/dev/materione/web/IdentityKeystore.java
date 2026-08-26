package dev.materione.web;

import com.google.gson.JsonObject;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.core.diagnostics.DiagnosticCodes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;

/**
 * The server's long-term editor identity (web.md §5.2): an Ed25519 keypair
 * persisted in {@code storage/} so returning editors recognize the server
 * across sessions. Browser keys are per-session; this one is durable and
 * part of what an operator backs up.
 *
 * <p>Fail-soft (D6): an unreadable or inconsistent keypair is replaced with
 * a freshly generated one and announced with {@code MTR-0037} — editors see
 * a new server identity, which costs a re-trust prompt, never a crash.
 */
public final class IdentityKeystore {

    private static final String FILE_NAME = "editor-identity.json";

    private final DiagnosticsBus diagnostics;
    private final SecureRandom random;

    public IdentityKeystore(DiagnosticsBus diagnostics, SecureRandom random) {
        this.diagnostics = diagnostics;
        this.random = random;
    }

    /** Loads the persisted identity, regenerating it when missing or damaged. */
    public KeyPair load(Path storageDir) {
        Path file = storageDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            try {
                JsonObject stored = dev.materione.web.protocol.Wire.parseObject(Files.readString(file));
                KeyPair pair = dev.materione.web.protocol.KeyFormat.ed25519KeyPair(
                        dev.materione.web.protocol.Wire.requiredBinary(stored, "seed"),
                        dev.materione.web.protocol.Wire.requiredBinary(stored, "pub"));
                // The constructor already proved seed↔pub consistency by a
                // sign/verify round trip, so this pair is good to use.
                return pair;
            } catch (IOException | RuntimeException e) {
                diagnostics.publish(new Diagnostic(
                        DiagnosticCodes.EDITOR_IDENTITY_REBUILT,
                        dev.materione.api.diagnostics.Severity.WARN,
                        "stored editor identity was unreadable (" + e.getMessage() + ") and has been replaced",
                        null, null, "returning editors will need to re-approve this server"));
            }
        }
        KeyPair fresh = dev.materione.web.protocol.KeyFormat.generateEd25519(random);
        persist(file, fresh);
        return fresh;
    }

    private void persist(Path file, KeyPair pair) {
        try {
            Files.createDirectories(file.getParent());
            JsonObject object = new JsonObject();
            dev.materione.web.protocol.Wire.putBinary(
                    object, "seed",
                    dev.materione.web.protocol.KeyFormat.ed25519RawSeed(pair.getPrivate()));
            dev.materione.web.protocol.Wire.putBinary(
                    object, "pub",
                    dev.materione.web.protocol.KeyFormat.ed25519RawPublic(pair.getPublic()));
            Files.writeString(file, dev.materione.web.protocol.Wire.canonicalJson(object));
        } catch (IOException e) {
            diagnostics.publish(new Diagnostic(
                    DiagnosticCodes.EDITOR_IDENTITY_REBUILT,
                    dev.materione.api.diagnostics.Severity.WARN,
                    "could not persist the editor identity (" + e.getMessage() + "); "
                            + "it will change on next boot",
                    null, null, "check permissions on " + file.getParent()));
        }
    }
}
