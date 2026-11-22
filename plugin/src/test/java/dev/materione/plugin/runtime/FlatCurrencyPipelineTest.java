package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.provider.Currency;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.pipeline.PackResources;
import dev.materione.core.registry.RegistryService;
import dev.materione.core.runtime.RuntimeCurrencies;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A flat currency file (#469, logic.md section 18) from the file to the live currency view: the showcase's
 * {@code points.yml} is the currency the shop pays with, and a flat file that names no integration is refused with
 * MTR-0062, which says both shapes a currency file may take.
 */
class FlatCurrencyPipelineTest {

    private static final Path SHOWCASE = GoldenFiles.fixturesRoot().resolve("packs").resolve("showcase");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    FlatCurrencyPipelineTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "player"));
        pipeline.registerBuiltins();
    }

    @Test
    void theShowcasePointsFileIsTheLiveCurrencyTheShopPaysWith() throws IOException {
        List<Currency> currencies = currenciesOf("showcase", SHOWCASE.resolve("currencies").resolve("points.yml"));
        assertThat(currencies).singleElement().satisfies(currency -> {
            assertThat(currency.id()).isEqualTo(NamespacedId.of("showcase", "points"));
            assertThat(currency.integration()).isEqualTo("xp");
            assertThat(currency.displayName()).isEqualTo("Points");
            assertThat(currency.decimals()).isZero();
        });
    }

    @Test
    void aFlatFileNamingNoIntegrationIsRefusedWithTheShapesItMayTake(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("gems.yml");
        Files.writeString(file, "display_name: Gems\n");
        assertThat(currenciesOf("shop", file)).isEmpty();
        assertThat(diagnostics.recent(64)).anySatisfy(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.CURRENCY_REJECTED);
            assertThat(d.message()).contains("shop:gems").contains("integration").contains("file name as the id");
        });
    }

    /**
     * The self-description is how the editor and the SDK learn which directories take a flat file (#475): a registered
     * type reports it exactly where the layout takes it, so the rule is stated once and read everywhere.
     */
    @Test
    void aTypeReportsTheFlatFormExactlyWhereTheLayoutTakesIt() {
        var types = pipeline.contentTypes();
        assertThat(types).isNotEmpty().allSatisfy(type -> assertThat(type.flatFile())
                .as(type.directory())
                .isEqualTo(dev.materione.core.pipeline.PackLayout.SINGLE_RESOURCE_DIRS.contains(type.directory())));
        assertThat(types).extracting(dev.materione.api.content.ContentType::directory)
                .containsAll(dev.materione.core.pipeline.PackLayout.SINGLE_RESOURCE_DIRS);
    }

    /** The live currencies of one pack's file, built the way a reload builds them: parse, then the shadow. */
    private List<Currency> currenciesOf(String packId, Path file) throws IOException {
        var declared = PackResources.declared(packId, "currencies", file,
                LosslessYaml.parse(Files.readString(file)));
        var manifest = new PackManifest(packId, "1", 1, List.of(), LosslessYaml.parse("id: " + packId + "\n"));
        var packs = List.of(new ContentPack(packId, file.getParent(), manifest, declared.resources()));
        return pipeline.buildShadow(packs).view(RuntimeCurrencies.KEY).currencies();
    }
}
