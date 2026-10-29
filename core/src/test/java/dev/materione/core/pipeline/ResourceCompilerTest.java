package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.CompileRejectException;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.registry.SelfDescription;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The shared per-resource sequence (plugin.md §4.2–§4.4): the diagnostics and quarantine entries it
 * raises, in order, for each outcome of parse, diagnose and compile. Pack and preview both depend
 * on these outcomes, so each one is pinned here.
 */
class ResourceCompilerTest {

    private static final NamespacedId RESOURCE = NamespacedId.of("demo", "thing");

    /** What a fake content type does at each step. */
    private static final class FakeType implements ContentType {

        final List<String> calls = new ArrayList<>();
        Optional<Object> parsed = Optional.of("parsed");
        RuntimeException parseFailure;
        List<Diagnostic> parts = List.of();
        RuntimeException diagnoseFailure;
        RuntimeException compileFailure;
        CompileRejectException compileReject;
        NamespacedId modelId;

        @Override
        public String name() {
            return "thing";
        }

        @Override
        public String directory() {
            return "things";
        }

        @Override
        public SelfDescription describe() {
            return SelfDescription.of("Thing", "a test type");
        }

        @Override
        public Optional<Object> parse(NamespacedId id, Map<String, Object> definition,
                Map<String, Map<String, Object>> templates) {
            calls.add("parse");
            if (parseFailure != null) {
                throw parseFailure;
            }
            return parsed;
        }

        @Override
        public List<Diagnostic> diagnose(Object parsedDefinition) {
            calls.add("diagnose");
            if (diagnoseFailure != null) {
                throw diagnoseFailure;
            }
            return parts;
        }

        @Override
        public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter,
                Allocations allocations) {
            calls.add("compile");
            modelId = allocatedModelId;
            if (compileReject != null) {
                throw compileReject;
            }
            if (compileFailure != null) {
                throw compileFailure;
            }
        }
    }

    /** Records what the sequence publishes and quarantines, in order. */
    private static final class Recorder implements ResourceCompiler.Sink {

        final List<String> log = new ArrayList<>();

        @Override
        public void publish(Diagnostic diagnostic) {
            log.add("publish " + diagnostic.code().code() + " " + diagnostic.message());
        }

        @Override
        public void quarantine(NamespacedId resource, Diagnostic diagnostic) {
            log.add("quarantine " + resource + " " + diagnostic.code().code());
        }
    }

    private static Optional<Object> run(FakeType type, Recorder sink) {
        return ResourceCompiler.compile(RESOURCE, Map.of("title", "x"), Map.of(), type, EMITTER, ALLOCATIONS, sink);
    }

    @Test
    void aCleanResourceParsesDiagnosesAndCompilesWithItsModelId() {
        var type = new FakeType();
        var sink = new Recorder();
        assertThat(run(type, sink)).contains("parsed");
        assertThat(type.calls).containsExactly("parse", "diagnose", "compile");
        assertThat(type.modelId).isEqualTo(NamespacedId.of("demo", "thing_model"));
        assertThat(sink.log).isEmpty();
    }

    @Test
    void aParseCrashIsOneDiagnosticAndAQuarantineEntryAndNothingElseRuns() {
        var type = new FakeType();
        type.parseFailure = new IllegalStateException("boom");
        var sink = new Recorder();
        assertThat(run(type, sink)).isEmpty();
        assertThat(type.calls).containsExactly("parse");
        assertThat(sink.log).hasSize(2);
        assertThat(sink.log.get(0)).startsWith("publish " + DiagnosticCodes.PIPELINE_STAGE_FAILED.code());
        assertThat(sink.log.get(1)).isEqualTo("quarantine demo:thing " + DiagnosticCodes.PIPELINE_STAGE_FAILED.code());
    }

    @Test
    void aDefinitionThatDoesNotParseIsReportedButNotQuarantined() {
        var type = new FakeType();
        type.parsed = Optional.empty();
        var sink = new Recorder();
        assertThat(run(type, sink)).isEmpty();
        assertThat(type.calls).containsExactly("parse");
        assertThat(sink.log).singleElement().satisfies(entry -> {
            assertThat(entry).startsWith("publish " + DiagnosticCodes.CONTENT_SCHEMA_INVALID.code());
            assertThat(entry).contains("failed to parse as thing");
        });
    }

    @Test
    void thePartsAreReportedInOrderBeforeTheCompile() {
        var type = new FakeType();
        type.parts = List.of(
                new Diagnostic(DiagnosticCodes.GUI_LAYER_SKIPPED, Severity.WARN, "first", RESOURCE, null, null),
                new Diagnostic(DiagnosticCodes.GUI_LAYER_SKIPPED, Severity.WARN, "second", RESOURCE, null, null));
        var sink = new Recorder();
        assertThat(run(type, sink)).contains("parsed");
        assertThat(sink.log).containsExactly(
                "publish " + DiagnosticCodes.GUI_LAYER_SKIPPED.code() + " first",
                "publish " + DiagnosticCodes.GUI_LAYER_SKIPPED.code() + " second");
        assertThat(type.calls).containsExactly("parse", "diagnose", "compile");
    }

    @Test
    void aCrashingDiagnoseSkipsTheCompileAndKeepsTheParsedDefinition() {
        var type = new FakeType();
        type.diagnoseFailure = new IllegalStateException("part");
        var sink = new Recorder();
        assertThat(run(type, sink)).contains("parsed");
        assertThat(type.calls).containsExactly("parse", "diagnose");
        assertThat(sink.log).hasSize(2);
    }

    @Test
    void aRejectedCompileIsPublishedThenQuarantinedWithItsOwnCode() {
        var type = new FakeType();
        type.compileReject = new CompileRejectException(DiagnosticCodes.GUI_BACKGROUND_EXHAUSTED, "no codepoint left",
                "retire unused GUIs");
        var sink = new Recorder();
        run(type, sink);
        assertThat(sink.log).containsExactly(
                "publish " + DiagnosticCodes.GUI_BACKGROUND_EXHAUSTED.code() + " no codepoint left",
                "quarantine demo:thing " + DiagnosticCodes.GUI_BACKGROUND_EXHAUSTED.code());
    }

    @Test
    void aCrashingCompileIsContainedToItsResource() {
        var type = new FakeType();
        type.compileFailure = new IllegalStateException("oops");
        var sink = new Recorder();
        assertThat(run(type, sink)).contains("parsed");
        assertThat(sink.log).hasSize(2);
        assertThat(sink.log.get(1)).isEqualTo("quarantine demo:thing " + DiagnosticCodes.PIPELINE_STAGE_FAILED.code());
    }

    private static final PackEmitter EMITTER = new PackEmitter() {
        @Override
        public void itemDefinition(NamespacedId id, Map<String, Object> itemModelJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void model(NamespacedId id, Map<String, Object> modelJson) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void copyAsset(Path sourceFile, String destination) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void vanillaFile(String path, Map<String, Object> json) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void description(String text) {
            throw new UnsupportedOperationException();
        }
    };
    private static final Allocations ALLOCATIONS = new Allocations() {
        @Override
        public String allocate(NamespacedId resource, String kind) {
            return "1";
        }

        @Override
        public Optional<String> lookup(NamespacedId resource, String kind) {
            return Optional.empty();
        }
    };
}
