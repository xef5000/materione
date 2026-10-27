package dev.materione.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperRuntimeCacheTest {
    @TempDir Path root;

    @Test
    void coldThenWarmCopiesOnlyBinariesAndKeepsIndependentFiles() throws Exception {
        Path pin = write("paper.jar", "pin");
        var cache = new PaperRuntimeCache(root.resolve("cache"), pin);
        Path source = root.resolve("source");
        assertThat(cache.restore(source)).isFalse();
        write("source/cache/mojang.jar", "mojang");
        write("source/libraries/lib.jar", "library");
        write("source/versions/paper.jar", "paper");
        write("source/world/level.dat", "world");
        write("source/plugins/Materione.jar", "plugin");
        write("source/plugins/Materione/storage/allocation.bin", "durable");
        write("source/libraries/not-a-jar.txt", "ignore");
        cache.capture(source);
        Path target = root.resolve("target");
        assertThat(cache.restore(target)).isTrue();
        assertThat(Files.readString(target.resolve("cache/mojang.jar"))).isEqualTo("mojang");
        assertThat(Files.exists(target.resolve("world"))).isFalse();
        assertThat(Files.exists(target.resolve("plugins"))).isFalse();
        assertThat(Files.exists(target.resolve("libraries/not-a-jar.txt"))).isFalse();
        Files.writeString(target.resolve("libraries/lib.jar"), "changed");
        assertThat(cache.restore(root.resolve("another"))).isTrue();
        assertThat(Files.readString(root.resolve("another/libraries/lib.jar"))).isEqualTo("library");
        Files.writeString(pin, "different pin");
        assertThat(new PaperRuntimeCache(root.resolve("cache"), pin).restore(root.resolve("bumped"))).isFalse();
    }

    @Test
    void corruptedCacheFailsExplicitlyBeforeSeedingAnyFiles() throws Exception {
        var cache = prepared();
        Path manifest = manifest();
        Path jar = manifest.getParent().resolve("libraries/lib.jar");
        Files.writeString(jar, "corruption");
        assertThatThrownBy(() -> cache.restore(root.resolve("target")))
                .isInstanceOf(IOException.class).hasMessageContaining("cache is invalid")
                .hasMessageContaining("-Pharness.runtimeCache=false");
        assertThat(Files.exists(root.resolve("target"))).isFalse();
    }

    @Test
    void incompleteAndEscapingManifestCannotBeRestored() throws Exception {
        var cache = prepared();
        Path manifest = manifest();
        Files.writeString(manifest, "0".repeat(64) + " libraries/../../outside.jar\n");
        assertThatThrownBy(() -> cache.restore(root.resolve("target")))
                .isInstanceOf(IOException.class).hasMessageContaining("invalid binary");
        Files.delete(manifest);
        assertThatThrownBy(() -> cache.restore(root.resolve("target"))).isInstanceOf(IOException.class);
    }

    @Test
    void unsuccessfulCaptureNeverPublishesAPartialEntry() throws Exception {
        Path pin = write("paper.jar", "pin");
        var cache = new PaperRuntimeCache(root.resolve("cache"), pin);
        write("source/cache/mojang.jar", "mojang");
        assertThatThrownBy(() -> cache.capture(root.resolve("source"))).isInstanceOf(IOException.class);
        assertThat(cache.restore(root.resolve("target"))).isFalse();
    }

    private PaperRuntimeCache prepared() throws Exception {
        Path pin = write("paper.jar", "pin");
        write("source/cache/mojang.jar", "mojang");
        write("source/libraries/lib.jar", "library");
        write("source/versions/paper.jar", "paper");
        var cache = new PaperRuntimeCache(root.resolve("cache"), pin);
        cache.capture(root.resolve("source"));
        return cache;
    }

    private Path manifest() throws IOException {
        try (var paths = Files.walk(root.resolve("cache"))) {
            return paths.filter(path -> path.getFileName().toString().equals("manifest.sha256"))
                    .findFirst().orElseThrow();
        }
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
