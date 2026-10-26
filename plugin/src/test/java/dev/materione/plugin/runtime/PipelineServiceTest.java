package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.ContentType;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.RegistryEntry;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.registry.RegistryService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PipelineServiceTest {

    private final RegistryService registries = new RegistryService();
    private final PipelineService pipeline = new PipelineService(
            null, new DiagnosticsBusService(64), new QuarantineTracker(), registries, null,
            new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    @Test
    void builtinsRegisterWithoutLivePaperRegistries() {
        pipeline.registerBuiltins();

        var registered = registeredItem();
        assertThat(registered.entry().describe()).isEqualTo(registered.description());
        assertThat(registered.description().parameters())
                .filteredOn(parameter -> parameter.id().equals("material"))
                .singleElement()
                .satisfies(material -> {
                    assertThat(material.type()).isEqualTo(ParameterType.STRING);
                    assertThat(material.allowedValues()).isEmpty();
                    assertThat(material.uiHint()).isEqualTo("material");
                });
    }

    @Test
    void injectedMaterialChoicesReachTheItemTypeAndRegistryMetadata() {
        pipeline.registerBuiltins(List.of("STICK", "PAPER", "STICK"));

        var registered = registeredItem();
        assertThat(registered.entry().describe()).isEqualTo(registered.description());
        assertThat(registered.description().parameters())
                .filteredOn(parameter -> parameter.id().equals("material"))
                .singleElement()
                .satisfies(material -> {
                    assertThat(material.type()).isEqualTo(ParameterType.ENUM);
                    assertThat(material.allowedValues()).containsExactly("PAPER", "STICK");
                    assertThat(material.uiHint()).isEqualTo("material");
                });
    }

    private RegistryEntry<ContentType> registeredItem() {
        return registries.registry(PipelineService.CONTENT_TYPES, ContentType.class)
                .get(NamespacedId.of("materione", "item"))
                .orElseThrow();
    }
}
