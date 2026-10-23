package dev.materione.content.effects;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.EffectSpec;
import dev.materione.api.content.PackEmitter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Text-effect behavior: parsing, signal allocation, spec emission. */
class EffectTypeTest {

    private final EffectType type = new EffectType();

    @Test
    void parsesWithDefaultsAndNormalizesKind() {
        var parsed = type.parse(
                NamespacedId.parse("sample:pulse"),
                map("kind", "Pulse", "speed", 1.5),
                Map.of());
        assertThat(parsed).isPresent();
        var effect = (EffectDefinition) parsed.get();
        assertThat(effect.kind()).isEqualTo("pulse");
        assertThat(effect.speed()).isEqualTo(1.5f);
        assertThat(effect.amplitude()).isEqualTo(1.0f);
        assertThat(effect.colors()).isEmpty();
    }

    @Test
    void unknownKindsAndTooManyColorsAreRejected() {
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("kind", "explode"), Map.of())).isEmpty();
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map(), Map.of())).isEmpty();
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            tooMany.add("#FF0000");
        }
        assertThat(type.parse(
                NamespacedId.parse("sample:x"), map("kind", "rainbow", "colors", tooMany), Map.of()))
                .isEmpty();
    }

    @Test
    void compileAllocatesADurableSignalAndEmitsTheSpec() {
        var parsed = type.parse(
                NamespacedId.parse("sample:rainbow"),
                map("kind", "rainbow", "colors", List.of("#FF0000", "#0000FF")),
                Map.of());
        var spec = compile((EffectDefinition) parsed.orElseThrow(), 1);
        assertThat(spec.id().toString()).isEqualTo("sample:rainbow");
        // slot 1 → the first reserved signal, one step below full magenta
        assertThat(spec.signalRgb()).isEqualTo(0xFF00FE);
        assertThat(spec.colors()).containsExactly("#FF0000", "#0000FF");
    }

    @Test
    void compileSignalsStepDownTheReservedBand() {
        var parsed = type.parse(NamespacedId.parse("sample:wave"), map("kind", "wave"), Map.of());
        EffectSpec second = compile((EffectDefinition) parsed.orElseThrow(), 2);
        assertThat(second.signalRgb()).isEqualTo(0xFF00FD);
    }

    @Test
    void exhaustedSignalSpaceIsRejectedWithMtr0047() {
        var parsed = type.parse(NamespacedId.parse("sample:wave"), map("kind", "wave"), Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(dev.materione.api.content.CompileRejectException.class,
                () -> compile((EffectDefinition) parsed.orElseThrow(), 255));
    }

    private EffectSpec compile(EffectDefinition effect, int slot) {
        return ((RecordingEmitter) captureCompile(effect, slot)).spec;
    }

    private PackEmitter captureCompile(EffectDefinition effect, int slot) {
        var emitter = new RecordingEmitter();
        type.compile(effect, null, emitter, new dev.materione.api.content.Allocations() {
            @Override
            public String allocate(NamespacedId resource, String kind) {
                return String.valueOf(slot);
            }

            @Override
            public Optional<String> lookup(NamespacedId resource, String kind) {
                return Optional.of(String.valueOf(slot));
            }
        });
        return emitter;
    }

    private static final class RecordingEmitter implements PackEmitter {
        EffectSpec spec;

        @Override
        public void textEffect(EffectSpec emitted) {
            this.spec = emitted;
        }

        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void model(NamespacedId id, Map<String, Object> json) {}

        @Override
        public void copyAsset(Path sourceFile, String destination) {}

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {}

        @Override
        public void description(String text) {}
    }

    private static Map<String, Object> map(Object... pairs) {
        var out = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }
}
