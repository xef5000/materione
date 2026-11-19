package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.materione.core.document.Scaffolds;
import dev.materione.core.storage.StorageService;
import dev.materione.testing.golden.GoldenFiles;
import dev.materione.web.protocol.Wire;
import dev.materione.web.rpc.RpcDispatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every content type's scaffold must be clean from its first save (issue 380): a freshly created resource runs
 * through the authoring transaction dry run with no diagnostics. A scaffold that writes a default reference the
 * pack cannot resolve (the furniture's {@code block/<id>} texture, MTR-0009) fails here instead of handing a new
 * user a document that is already broken.
 */
class ScaffoldDryRunTest {

    @TempDir
    Path temp;

    @AfterEach
    void close() {
        EditorDemoSnapshotTest.closeAll();
    }

    /**
     * Types whose scaffold still dry-runs with a diagnostic, each with the reason. These are required-asset types: the
     * parameter is required (a scaffold without it does not parse) and it names an uploaded file a fresh pack does not
     * have, and no shipped vanilla asset is a sound or painting the starter could honestly point at. Fixing them needs
     * a design change (a placeholder asset written with the scaffold, or an upload-first create flow), not a one-line
     * default, so they are excluded here and {@link #excludedTypesStillNeedTheirExclusion} keeps the list honest.
     */
    static final java.util.Map<String, String> EXCLUDED = java.util.Map.of(
            "glyphs", "required texture under assets/textures/gui/; a fresh pack has no glyph image (MTR-0009)",
            "paintings", "required texture under assets/textures/painting/ sized to width x height; a fresh pack has no painting image (MTR-0009)",
            "sounds", "required sound source must be an uploaded file under assets/sounds/ (MTR-0040)");

    static java.util.stream.Stream<String> scaffoldTypes() {
        return Scaffolds.SCAFFOLD_TYPES.stream().filter(type -> !EXCLUDED.containsKey(type));
    }

    /** An excluded type that becomes clean must leave the list, so the exclusion cannot silently outlive its reason. */
    @ParameterizedTest(name = "{0} exclusion still needed")
    @MethodSource("excludedTypes")
    void excludedTypesStillNeedTheirExclusion(String type) throws IOException {
        JsonObject report = dryRunScaffold(type);
        assertThat(report.getAsJsonArray("diagnostics").asList())
                .as("%s is excluded (%s) but its scaffold dry-runs clean now; remove the exclusion", type, EXCLUDED.get(type))
                .isNotEmpty();
    }

    static java.util.stream.Stream<String> excludedTypes() {
        return EXCLUDED.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0} scaffold dry-runs clean")
    @MethodSource("scaffoldTypes")
    void scaffoldDryRunsWithNoDiagnostics(String type) throws IOException {
        JsonObject report = dryRunScaffold(type);
        assertThat(report.getAsJsonArray("diagnostics").asList())
                .as("%s scaffold dry run diagnostics", type)
                .isEmpty();
        assertThat(report.get("accepted").getAsBoolean()).as("%s scaffold accepted", type).isTrue();
    }

    private JsonObject dryRunScaffold(String type) throws IOException {
        Path root = Files.createTempDirectory(temp, "server");
        StorageService.StorageState storage = new StorageService().init(root);
        for (String fixturePack : EditorDemoSnapshotTest.PACKS) {
            EditorDemoSnapshotTest.copyTree(GoldenFiles.fixturesRoot().resolve("packs").resolve(fixturePack),
                    storage.contentPacksDir().resolve(fixturePack));
        }
        RpcDispatcher dispatcher = EditorDemoSnapshotTest.realOps(root, storage, true);

        JsonObject create = new JsonObject();
        create.addProperty("kind", "create");
        create.addProperty("type", type);
        create.addProperty("id", "probe_" + type);
        JsonArray operations = new JsonArray();
        operations.add(create);
        JsonObject request = new JsonObject();
        request.addProperty("pack", "sample");
        request.addProperty("dryRun", true);
        request.add("operations", operations);

        Wire.Response response = dispatcher.dispatch(new Wire.Request("authoring.transaction", "scaffold", request));
        assertThat(response.ok()).as("authoring.transaction: " + response.error()).isTrue();
        return response.data().getAsJsonObject();
    }
}
