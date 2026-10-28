package dev.materione.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

/**
 * The golden wire-protocol fixture ({@code fixtures/protocol/
 * editor-protocol-v1.json}) — the drift guard between this JVM
 * implementation and every future implementation of the same protocol (the
 * browser SPA now, the Cloudflare Worker tests at M5; web.md §9 "protocol
 * fixtures: golden handshake/frames checked in; both sides run against the
 * same files").
 *
 * <p>Everything derives from fixed key material (the Ed25519 halves are RFC
 * 8032 test vectors), fixed scalars and fixed nonces, so regeneration is
 * byte-stable. Regenerate with {@code -Dmaterione.golden.regen=true} and
 * review the diff; CI never regenerates.
 */
class GoldenProtocolFixturesTest {

    private static final String FILE_NAME = "editor-protocol-v1.json";

    // RFC 8032 §7.1 TEST 2 — the plugin's signing identity.
    private static final byte[] PLUGIN_SEED = hex(
            "4ccd089b28ff96da" + "9db6c346ec114e0f" + "5b8a319f35aba624" + "da8cf6ed4fb8a6fb");
    private static final byte[] PLUGIN_PUB = hex(
            "3d4017c3e843895a" + "92b70aa74d1b7ebc" + "9c982ccf2ec4968c" + "c0cd55f12af4660c");
    // RFC 8032 §7.1 TEST 1 — the editor's signing identity.
    private static final byte[] EDITOR_SEED = hex(
            "9d61b19deffd5a60" + "ba844af492ec2cc4" + "4449c5697b326919" + "703bac031cae7f60");
    private static final byte[] EDITOR_PUB = hex(
            "d75a980182b10ab7" + "d54bfed3c964073a" + "0ee172f3daa62325" + "af021a68f707511a");

    private static final byte[] PLUGIN_SCALAR = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static final byte[] EDITOR_SCALAR = hex(
            "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100");
    private static final byte[] WRAPPING_KEY = filled(32, 0x20);
    private static final String SESSION_ID = "AAECAwQFBgcICQoLDA0ODw";

    @Test
    void goldenFixtureIsStable() throws IOException {
        Path target = GoldenFiles.fixturesRoot().resolve("protocol").resolve(FILE_NAME);
        if (GoldenFiles.regenEnabled()) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, pretty(build()), StandardCharsets.UTF_8);
            return;
        }
        assertThat(target).exists().withFailMessage(
                "missing " + target + " — regenerate once with -Dmaterione.golden.regen=true");
        assertThat(pretty(build())).isEqualTo(Files.readString(target, StandardCharsets.UTF_8));
    }

    /**
     * Behavioral check against the committed fixture — the exact steps a
     * second runtime performs on these vectors: reconstruct keys from raw
     * material, verify the transcript hash and confirmation digest, open the
     * wrapped session blob, verify and decrypt both frames in order.
     */
    @Test
    void fixtureMaterialReconstructsAndVerifies() throws IOException {
        JsonObject fixture = loadFixture();

        JsonObject handshake = fixture.getAsJsonObject("handshake");
        HelloMessage hello = HelloMessage.fromJson(handshake.getAsJsonObject("hello"));
        HelloReplyMessage reply = HelloReplyMessage.fromJson(handshake.getAsJsonObject("hello-reply"));

        KeyPair pluginSigning = KeyFormat.ed25519KeyPair(PLUGIN_SEED, PLUGIN_PUB);
        byte[] transcriptHash = SessionCrypto.transcriptHash(hello.transcriptPart(), reply.transcriptPart());
        assertThat(Wire.encode(transcriptHash))
                .isEqualTo(handshake.getAsJsonPrimitive("transcriptHash").getAsString());

        KeyPair editorEphemeral = KeyFormat.p256FromScalar(Wire.decode(fixture.getAsJsonObject("p256")
                .getAsJsonPrimitive("editorScalar").getAsString()));
        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                editorEphemeral.getPrivate(), KeyFormat.p256Public(reply.ecdhPub()));
        SecretKey sessionKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);

        assertThat(SessionCrypto.confirmationsMatch(
                reply.confirmation(), SessionCrypto.keyConfirmation(sharedSecret, transcriptHash)))
                .isTrue();

        FrameCodec receiver = FrameCodec.receiver();
        var frames = fixture.getAsJsonObject("frames");
        for (String index : List.of("frame0", "frame1")) {
            byte[] body = receiver.open(sessionKey, pluginSigning.getPublic(),
                    frames.getAsJsonObject(index));
            String plaintext = new String(body, StandardCharsets.UTF_8);
            assertThat(plaintext)
                    .isEqualTo(Wire.canonicalJson(frames.getAsJsonObject("body" + index.charAt(5))));
        }

        SecretKey wrapping = new javax.crypto.spec.SecretKeySpec(
                Wire.decode(fixture.getAsJsonObject("sessionBlob")
                        .getAsJsonPrimitive("wrappingKey").getAsString()),
                "AES");
        Envelope.Opened opened = Envelope.open(
                wrapping, hello.sessionId(), fixture.getAsJsonObject("sessionBlob").getAsJsonObject("envelope"));
        assertThat(opened.type()).isEqualTo(SessionBlob.TYPE);
        SessionBlob blob = SessionBlob.fromBodyBytes(opened.body());
        assertThat(blob.sessionId()).isEqualTo(hello.sessionId());
        assertThat(blob.protocolVersions()).containsExactly(Wire.VERSION);
        assertThat(blob.capabilities()).containsExactlyElementsOf(SessionBlob.CURRENT_CAPABILITIES);

        // The probe: the request parses and the reply verifies under the plugin key.
        JsonObject probe = fixture.getAsJsonObject("probe");
        ProbeMessage.Request probeRequest =
                ProbeMessage.parse(Wire.canonicalJson(probe.getAsJsonObject("request")));
        byte[] signed = ProbeMessage.signedBytes(probeRequest.sessionId(), probeRequest.challenge());
        assertThat(new String(signed, StandardCharsets.UTF_8))
                .isEqualTo(probe.getAsJsonPrimitive("signedText").getAsString());
        assertThat(SessionCrypto.verify(
                pluginSigning.getPublic(), signed,
                Wire.requiredBinary(probe.getAsJsonObject("reply"), "sig"))).isTrue();
    }

    // ------------------------------------------------------------------
    // deterministic construction

    private JsonObject build() {
        KeyPair pluginSigning = KeyFormat.ed25519KeyPair(PLUGIN_SEED, PLUGIN_PUB);
        KeyPair pluginEphemeral = KeyFormat.p256FromScalar(PLUGIN_SCALAR);
        KeyPair editorEphemeral = KeyFormat.p256FromScalar(EDITOR_SCALAR);

        HelloMessage hello = new HelloMessage(
                filled(16, 0x10), SESSION_ID, Wire.VERSION, EDITOR_PUB,
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()),
                "golden-fixture");
        HelloReplyMessage shape = new HelloReplyMessage(
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) pluginEphemeral.getPublic()),
                new byte[0]);
        byte[] transcriptHash =
                SessionCrypto.transcriptHash(hello.transcriptPart(), shape.transcriptPart());
        byte[] sharedSecret = SessionCrypto.ecdhSharedSecret(
                pluginEphemeral.getPrivate(), KeyFormat.p256Public(hello.editorEphemeralPubKey()));
        byte[] confirmation = SessionCrypto.keyConfirmation(sharedSecret, transcriptHash);
        HelloReplyMessage reply = new HelloReplyMessage(shape.ecdhPub(), confirmation);
        SecretKey sessionKey = SessionCrypto.sessionKey(sharedSecret, transcriptHash);

        // Two plugin→editor frames over canonical RPC bodies; nonces come off
        // a deterministic queue so regeneration reproduces every byte.
        Wire.Request request = Wire.Request.fromJson(
                Wire.parseObject("{\"op\":\"browse.listPacks\",\"id\":\"f1\",\"payload\":{}}"));
        Wire.Response response = Wire.Response.success(request, Wire.parse("{\"packs\":[\"sample\"]}"));
        byte[] body0 = Wire.canonicalBytes(request.toJson());
        byte[] body1 = Wire.canonicalBytes(response.toJson());

        FrameCodec sender = FrameCodec.sender();
        Supplier<byte[]> nonces = queueOf(1, 2);
        JsonObject frame0 = sender.seal(sessionKey, pluginSigning.getPrivate(), body0, nonces);
        JsonObject frame1 = sender.seal(sessionKey, pluginSigning.getPrivate(), body1, nonces);

        JsonObject frames = new JsonObject();
        frames.add("body0", request.toJson());
        frames.add("frame0", frame0);
        frames.add("body1", response.toJson());
        frames.add("frame1", frame1);

        JsonObject ed25519Section = new JsonObject();
        Wire.putBinary(ed25519Section, "pluginSeed", PLUGIN_SEED);
        Wire.putBinary(ed25519Section, "pluginPub", PLUGIN_PUB);
        Wire.putBinary(ed25519Section, "editorSeed", EDITOR_SEED);
        Wire.putBinary(ed25519Section, "editorPub", EDITOR_PUB);

        JsonObject p256Section = new JsonObject();
        Wire.putBinary(p256Section, "pluginScalar", PLUGIN_SCALAR);
        Wire.putBinary(p256Section, "pluginEphemeralPub",
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) pluginEphemeral.getPublic()));
        Wire.putBinary(p256Section, "editorScalar", EDITOR_SCALAR);
        Wire.putBinary(p256Section, "editorEphemeralPub",
                KeyFormat.p256RawPublic((java.security.interfaces.ECPublicKey) editorEphemeral.getPublic()));
        Wire.putBinary(p256Section, "sharedSecret", sharedSecret);

        JsonObject handshake = new JsonObject();
        handshake.add("hello", hello.toJson());
        handshake.add("hello-reply", reply.toJson());
        Wire.putBinary(handshake, "transcriptHash", transcriptHash);
        Wire.putBinary(handshake, "sessionKey", sessionKey.getEncoded());
        Wire.putBinary(handshake, "confirmation", confirmation);

        SessionBlob blob = new SessionBlob(
                SESSION_ID, List.of(Wire.VERSION), SessionBlob.CURRENT_CAPABILITIES, PLUGIN_PUB, null, null);
        JsonObject envelope = Envelope.seal(
                new javax.crypto.spec.SecretKeySpec(WRAPPING_KEY, "AES"),
                SESSION_ID, SessionBlob.TYPE, blob.bodyBytes(), queueOf(0x40));
        JsonObject blobSection = new JsonObject();
        Wire.putBinary(blobSection, "wrappingKey", WRAPPING_KEY);
        blobSection.add("envelope", envelope);
        blobSection.add("plaintext", blob.toJson());

        // Direct-mode reachability probe (web.md §4.4): Ed25519 is deterministic,
        // so the signature is byte-stable and the browser verifies it verbatim.
        String probeChallengeText = Wire.encode(filled(16, 0x77));
        byte[] probeSigned = ProbeMessage.signedBytes(SESSION_ID, probeChallengeText);
        JsonObject probeRequest = new JsonObject();
        probeRequest.addProperty("type", ProbeMessage.TYPE);
        probeRequest.addProperty("sessionId", SESSION_ID);
        probeRequest.addProperty("challenge", probeChallengeText);
        JsonObject probe = new JsonObject();
        probe.add("request", probeRequest);
        probe.addProperty("signedText", new String(probeSigned, StandardCharsets.UTF_8));
        probe.add("reply", ProbeMessage.reply(
                SESSION_ID, SessionCrypto.sign(pluginSigning.getPrivate(), probeSigned)));

        JsonObject meta = new JsonObject();
        meta.addProperty("name", "materione editor wire protocol");
        meta.addProperty("version", Wire.VERSION);
        meta.addProperty("file", FILE_NAME);

        JsonObject root = new JsonObject();
        root.add("meta", meta);
        root.add("ed25519", ed25519Section);
        root.add("p256", p256Section);
        root.add("handshake", handshake);
        root.add("sessionBlob", blobSection);
        root.add("frames", frames);
        root.add("probe", probe);
        return root;
    }

    private JsonObject loadFixture() throws IOException {
        Path target = GoldenFiles.fixturesRoot().resolve("protocol").resolve(FILE_NAME);
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            Files.writeString(target, pretty(build()), StandardCharsets.UTF_8);
        }
        return Wire.parseObject(Files.readString(target, StandardCharsets.UTF_8));
    }

    /** Pretty-printed with sorted keys: canonicalize first, then print that. */
    private static String pretty(JsonObject root) {
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(Wire.parse(Wire.canonicalJson(root))) + "\n";
    }

    private static Supplier<byte[]> queueOf(int... fills) {
        var queue = new java.util.ArrayDeque<byte[]>();
        for (int fill : fills) {
            queue.add(filled(12, fill)); // GCM nonces are 12 bytes
        }
        return queue::poll;
    }

    private static byte[] filled(int length, int fill) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) fill);
        return bytes;
    }

    private static byte[] hex(String digits) {
        if (digits.length() % 2 != 0 || !digits.matches("[0-9a-f]+")) {
            throw new AssertionError("bad hex literal (length " + digits.length() + "): " + digits);
        }
        byte[] out = new byte[digits.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
