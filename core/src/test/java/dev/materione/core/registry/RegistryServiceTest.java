package dev.materione.core.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.NamespacedId;
import dev.materione.api.registry.Registry;
import dev.materione.api.registry.RegistryException;
import dev.materione.api.registry.RegistryKey;
import dev.materione.api.registry.SelfDescription;
import org.junit.jupiter.api.Test;

class RegistryServiceTest {

    record Dummy(String name) {}

    private static final RegistryKey KEY = new RegistryKey("mechanics");
    private static final SelfDescription DESC = SelfDescription.of("Dummy", "test entry");

    @Test
    void registerAndLookup() {
        RegistryService service = new RegistryService();
        Registry<Dummy> registry = service.registry(KEY, Dummy.class);
        registry.register(NamespacedId.of("materione", "backpack"), new Dummy("backpack"), DESC, "built-in");

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.get(NamespacedId.of("materione", "backpack")))
                .hasValueSatisfying(entry -> {
                    assertThat(entry.registeredBy()).isEqualTo("built-in");
                    assertThat(entry.description().displayName()).isEqualTo("Dummy");
                });
        assertThat(service.totalEntries()).isEqualTo(1);
    }

    @Test
    void duplicateIdRejectedWithPriorRegistrant() {
        RegistryService service = new RegistryService();
        Registry<Dummy> registry = service.registry(KEY, Dummy.class);
        registry.register(NamespacedId.of("ns", "x"), new Dummy("a"), DESC, "plugin-A");
        assertThatThrownBy(() -> registry.register(NamespacedId.of("ns", "x"), new Dummy("b"), DESC, "plugin-B"))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("already registered by plugin-A");
    }

    @Test
    void minecraftNamespaceRejected() {
        RegistryService service = new RegistryService();
        Registry<Dummy> registry = service.registry(KEY, Dummy.class);
        assertThatThrownBy(() ->
                registry.register(NamespacedId.parse("minecraft:x"), new Dummy("x"), DESC, "whoever"))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void freezeClosesWindowAndReopenReopensIt() {
        RegistryService service = new RegistryService();
        Registry<Dummy> registry = service.registry(KEY, Dummy.class);
        registry.register(NamespacedId.of("ns", "x"), new Dummy("x"), DESC, "t");

        service.freezeAll();
        assertThat(registry.isFrozen()).isTrue();
        assertThatThrownBy(() -> registry.register(NamespacedId.of("ns", "y"), new Dummy("y"), DESC, "t"))
                .isInstanceOf(RegistryException.class)
                .hasMessageContaining("frozen");

        service.reopenAllWindows();
        assertThat(registry.isFrozen()).isFalse();
        registry.register(NamespacedId.of("ns", "y"), new Dummy("y"), DESC, "t");
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void sameKeySameTypeReturnsSameRegistry_wrongTypeRejected() {
        RegistryService service = new RegistryService();
        Registry<Dummy> first = service.registry(KEY, Dummy.class);
        Registry<Dummy> second = service.registry(KEY, Dummy.class);
        assertThat(second).isSameAs(first);

        assertThatThrownBy(() -> service.registry(KEY, String.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exists with entry type");
    }

    @Test
    void doctorLinesReportState() {
        RegistryService service = new RegistryService();
        service.registry(KEY, Dummy.class).register(NamespacedId.of("ns", "x"), new Dummy("x"), DESC, "t");
        service.freezeAll();
        assertThat(service.doctorLines())
                .singleElement()
                .asString()
                .contains("mechanics", "1 entries", "frozen");
    }
}
