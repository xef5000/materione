package dev.materione.core.assets;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.ApiResult;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.core.authoring.ReloadHook;
import dev.materione.core.document.AssetStore;
import dev.materione.core.document.AuditLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The asset API against a real packs root: caps, path rules, the read-only
 * thumbnail cache, atomic replacement and the pack-wide reload every
 * mutation queues (api.md §3.4, web.md §4.2/§4.3).
 */
class AssetApiImplTest {

    @TempDir
    Path temp;

    private final RecordingReloads reloads = new RecordingReloads();

    private AssetApi api() {
        return new AssetApiImpl(
                new AssetStore(temp.resolve("packs"), new AuditLog(temp.resolve("audit.log"))),
                temp.resolve("packs"),
                reloads);
    }

    private static ActorContext editor() {
        return ActorContext.editorSession("session-1", null, node -> true);
    }

    // ------------------------------------------------------------------

    @Test
    void putUnderCapsAuditsAndQueuesAPackReload() throws IOException {
        AssetApi api = api();
        byte[] texture = "sample-png-data".getBytes(StandardCharsets.UTF_8);

        ApiResult<Integer> put = api.putAsset(
                editor(), "demo", "assets/textures/ruby.png", texture);

        assertThat(put.ok()).isTrue();
        assertThat(put.value()).isEqualTo(texture.length);
        // asset changes reload the whole pack (a texture can affect any item)
        assertThat(reloads.packs).containsExactly("demo");
        assertThat(reloads.files).containsExactly(temp.resolve("packs").resolve("demo"));
        assertThat(Files.readString(temp.resolve("audit.log"))).contains("assets.put");
    }

    @Test
    void oversizedUploadsAreRejectedByKind() throws IOException {
        AssetApi api = api();
        byte[] overTextureCap = new byte[AssetStore.MAX_ASSET_BYTES + 1];
        byte[] overAudioCap = new byte[AssetStore.MAX_AUDIO_BYTES + 1];
        byte[] bulkyAudio = new byte[AssetStore.MAX_ASSET_BYTES + 1]; // ok for audio

        ApiResult<Integer> texture = api.putAsset(
                editor(), "demo", "assets/textures/huge.png", overTextureCap);
        ApiResult<Integer> audio = api.putAsset(
                editor(), "demo", "assets/sounds/song.ogg", bulkyAudio);
        ApiResult<Integer> hugeAudio = api.putAsset(
                editor(), "demo", "assets/sounds/epic.ogg", overAudioCap);

        assertThat(texture.ok()).isFalse();
        assertThat(texture.diagnostics().get(0).code().code()).isEqualTo("MTR-0008");
        assertThat(audio.ok()).as("audio rides the 8 MiB cap").isTrue();
        assertThat(hugeAudio.ok()).isFalse();
        assertThat(temp.resolve("packs/demo/assets/textures/huge.png")).doesNotExist();
    }

    @Test
    void pathsMustStayInsideAssets() {
        AssetApi api = api();

        ApiResult<Integer> escape = api.putAsset(
                editor(), "demo", "assets/../pack.yml", "x".getBytes(StandardCharsets.UTF_8));
        ApiResult<byte[]> outside = api.getAsset("demo", "../../etc/passwd");
        ApiResult<Integer> thumb = api.putAsset(editor(), "demo",
                ".materione/thumbnails/gem.png", "x".getBytes(StandardCharsets.UTF_8));

        assertThat(escape.ok()).isFalse();
        assertThat(escape.diagnostics().get(0).code().code()).isEqualTo("MTR-0008");
        assertThat(outside.ok()).isFalse();
        assertThat(outside.diagnostics().get(0).code().code()).isEqualTo("MTR-0008");
        assertThat(thumb.ok()).as("authoring metadata is never a free-form upload target")
                .isFalse();
    }

    @Test
    void thumbnailCacheIsReadableButNotWritable() throws IOException {
        AssetApi api = api();
        Path thumbnail = temp.resolve("packs/demo/.materione/thumbnails/gem.png");
        Files.createDirectories(thumbnail.getParent());
        byte[] render = "render".getBytes(StandardCharsets.UTF_8);
        Files.write(thumbnail, render);

        ApiResult<byte[]> read = api.getAsset("demo", ".materione/thumbnails/gem.png");

        assertThat(read.ok()).isTrue();
        assertThat(read.value()).isEqualTo(render);
    }

    @Test
    void listGetMoveAndDeleteFormALifecycle() throws IOException {
        AssetApi api = api();
        api.putAsset(editor(), "demo", "assets/textures/ruby.png",
                "one".getBytes(StandardCharsets.UTF_8));

        // atomic replace: the same path can be rewritten in place
        ApiResult<Integer> replaced = api.putAsset(editor(), "demo", "assets/textures/ruby.png",
                "two".getBytes(StandardCharsets.UTF_8));
        assertThat(replaced.value()).isEqualTo(3);
        assertThat(api.getAsset("demo", "assets/textures/ruby.png").value())
                .isEqualTo("two".getBytes(StandardCharsets.UTF_8));

        List<AssetApi.AssetInfo> listed = api.listAssets("demo", null).value();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path()).isEqualTo("assets/textures/ruby.png");
        assertThat(listed.get(0).kind()).isEqualTo("png");
        assertThat(listed.get(0).sizeBytes()).isEqualTo(3);

        // substring query filters
        assertThat(api.listAssets("demo", "nope").value()).isEmpty();
        assertThat(api.listAssets("demo", "RUBY").value()).hasSize(1);

        assertThat(api.moveAsset(editor(), "demo",
                "assets/textures/ruby.png", "assets/textures/ruby_gem.png").ok()).isTrue();
        assertThat(api.getAsset("demo", "assets/textures/ruby.png").ok()).isFalse();
        assertThat(api.getAsset("demo", "assets/textures/ruby_gem.png").ok()).isTrue();

        // move onto an existing target is refused
        api.putAsset(editor(), "demo", "assets/textures/other.png",
                "x".getBytes(StandardCharsets.UTF_8));
        assertThat(api.moveAsset(editor(), "demo",
                "assets/textures/other.png", "assets/textures/ruby_gem.png").ok()).isFalse();

        assertThat(api.deleteAsset(editor(), "demo", "assets/textures/ruby_gem.png").ok()).isTrue();
        assertThat(api.getAsset("demo", "assets/textures/ruby_gem.png").ok()).isFalse();

        // deleting a missing file succeeds silently and queues no reload
        int reloadsBefore = reloads.packs.size();
        assertThat(api.deleteAsset(editor(), "demo", "assets/textures/ruby_gem.png").ok()).isTrue();
        assertThat(reloads.packs).hasSize(reloadsBefore);

        String audit = Files.readString(temp.resolve("audit.log"));
        assertThat(audit).contains("assets.move").contains("assets.delete");
    }

    private static final class RecordingReloads implements ReloadHook {
        final List<Path> files = new ArrayList<>();
        final List<String> packs = new ArrayList<>();

        @Override
        public void afterChange(ActorContext actor, String pack, Path changedFile) {
            files.add(changedFile);
            packs.add(pack);
        }
    }
}
