package dev.materione.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.pipeline.ReloadScope;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shape of a document in {@code currencies/} (logic.md section 18, #469). A currency file is keyed by currency id,
 * or it is one currency as top-level fields with the file name as the id. A file that declares no resource, or that
 * mixes the two shapes, is reported with the shape it should have; nothing is dropped without a diagnostic.
 */
class CurrencyFileShapeTest {

    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(256);
    private final QuarantineTracker quarantine = new QuarantineTracker();
    private final PipelineRunner runner = new PipelineRunner(diagnostics, quarantine);

    @Test
    void theShowcasePointsFileDefinesTheCurrencyTheShopPaysWith() {
        var result = runner.request(new ReloadScope.Everything(), GoldenFiles.fixturesRoot().resolve("packs"));
        assertThat(result.completed()).isTrue();
        assertThat(currencyIds()).contains("showcase:points", "economy:coins");
        assertThat(shapeDiagnostics()).noneMatch(d -> d.message().contains("points.yml"));
    }

    @Test
    void aFlatCurrencyFileDefinesOneCurrencyNamedByTheFile(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "currencies/gems.yml", """
                integration: xp
                display_name: Gems
                decimals: 0
                """);
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(currencyIds()).containsExactly("shop:gems");
        assertThat(definitionOf("shop:gems")).containsEntry("integration", "xp").containsEntry("display_name", "Gems");
        assertThat(quarantine.size()).isZero();
        assertThat(shapeDiagnostics()).isEmpty();
    }

    @Test
    void aFlatCurrencyFileWithAnEmptyFieldStillDefinesItsCurrency(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "currencies/gems.yml", "integration: xp\nsymbol:\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(currencyIds()).containsExactly("shop:gems");
        assertThat(definitionOf("shop:gems")).containsEntry("integration", "xp");
    }

    @Test
    void aFlatCurrencyFileIsKeptAsWrittenAfterLoad(@TempDir Path packsRoot) throws IOException {
        String text = "integration: xp\ndisplay_name: Gems\n# the decimals are whole\ndecimals: 0\n";
        Path file = writePack(packsRoot, "shop", "currencies/gems.yml", text);
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(Files.readString(file)).isEqualTo(text);
        assertThat(resource("shop:gems").document().save()).isEqualTo(text);
    }

    @Test
    void theReadViewOfAFlatFileRefusesEditsAndKeepsTheFileText(@TempDir Path packsRoot) throws IOException {
        String text = "integration: xp\ndisplay_name: Gems\n";
        writePack(packsRoot, "shop", "currencies/gems.yml", text);
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        var view = resource("shop:gems").document();
        assertThatThrownBy(() -> view.withScalar("Jewels", "gems", "display_name"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> view.withoutNode("gems", "display_name"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(view.save()).isEqualTo(text);
    }

    @Test
    void aKeyedCurrencyFileDefinesEachKey(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "currencies/bank.yml", """
                gems:
                  integration: xp
                coins:
                  integration: xp
                """);
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(currencyIds()).containsExactlyInAnyOrder("shop:gems", "shop:coins");
        assertThat(shapeDiagnostics()).isEmpty();
    }

    @Test
    void aFlatCurrencyFileWhoseNameIsNotAnIdIsReported(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "currencies/Gems.yml", "integration: xp\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(currencyIds()).isEmpty();
        assertThat(shapeDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("Gems.yml").contains("not a valid id");
        });
    }

    @Test
    void aCurrencyFileWithNoResourceIsReportedWithTheShapeItShouldHave(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "currencies/empty.yml", "# nothing yet\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(currencyIds()).isEmpty();
        assertThat(shapeDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("declares no currency").contains("empty.yml");
        });
    }

    @Test
    void aCurrencyFileMixingIdsAndFieldsKeepsItsCurrenciesAndReportsTheFields(@TempDir Path packsRoot)
            throws IOException {
        // the golden fault fixture: a keyed currency beside top-level fields
        writePack(packsRoot, "shop", "currencies/mixed.yml",
                Files.readString(GoldenFiles.fixturesRoot().resolve("faults").resolve("currency-mixed-shape.yml")));
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(currencyIds()).containsExactly("shop:gems");
        assertThat(shapeDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("mixes").contains("mixed.yml");
        });
    }

    @Test
    void fieldsInAKeyedDirectoryDefineNothingAndAreReported(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "items/sword.yml", "material: STICK\ndisplay_name: Sword\n");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(resourceIds("item")).isEmpty();
        assertThat(shapeDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("declares no item").contains("sword.yml");
        });
    }

    @Test
    void aTopLevelKeyWithNoValueDoesNotAbortTheReload(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "items/sword.yml", "sword:\nbow:\n  material: BOW\n");
        var result = runner.request(new ReloadScope.Everything(), packsRoot);
        assertThat(result.completed()).isTrue();
        assertThat(resourceIds("item")).containsExactly("shop:bow");
        assertThat(shapeDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("'sword'").contains("no fields");
        });
    }

    @Test
    void anEmptyFileInAKeyedDirectoryIsReported(@TempDir Path packsRoot) throws IOException {
        writePack(packsRoot, "shop", "recipes/blank.yml", "");
        assertThat(runner.request(new ReloadScope.Everything(), packsRoot).completed()).isTrue();
        assertThat(shapeDiagnostics()).singleElement().satisfies(d -> {
            assertThat(d.message()).contains("declares no recipe");
        });
    }

    private List<String> currencyIds() {
        return resourceIds("currency");
    }

    private List<String> resourceIds(String type) {
        return runner.lastPacks().stream()
                .flatMap(pack -> pack.resources().stream())
                .filter(resource -> resource.contentType().equals(type))
                .map(resource -> resource.id().toString())
                .toList();
    }

    private ContentResource resource(String id) {
        return runner.lastPacks().stream()
                .flatMap(pack -> pack.resources().stream())
                .filter(resource -> resource.id().toString().equals(id))
                .findFirst()
                .orElseThrow();
    }

    /** The definition a consumer reads for one resource: the document's mapping under the resource's id. */
    private Map<String, Object> definitionOf(String id) {
        var resource = resource(id);
        assertThat(resource.document().data().get(resource.id().value())).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = (Map<String, Object>) resource.document().data().get(resource.id().value());
        return definition;
    }

    private List<Diagnostic> shapeDiagnostics() {
        return diagnostics.recent(256).stream()
                .filter(d -> d.code() == DiagnosticCodes.CONTENT_SCHEMA_INVALID)
                .toList();
    }

    private static Path writePack(Path packsRoot, String packId, String relative, String text) throws IOException {
        Path pack = packsRoot.resolve(packId);
        Path file = pack.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.writeString(pack.resolve("pack.yml"), "id: " + packId + "\nversion: 1.0.0\nschema_version: 1\n");
        return file;
    }
}
