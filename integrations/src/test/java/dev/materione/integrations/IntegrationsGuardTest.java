package dev.materione.integrations;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.DiagnosticsBus;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.provider.EconomyProvider;
import dev.materione.api.logic.provider.LogicProviders;
import dev.materione.api.logic.provider.PermissionProvider;
import dev.materione.api.logic.provider.ProviderResult;
import dev.materione.api.logic.provider.ServerTransfer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.Test;

/**
 * The soft-depend gate (logic.md §4.5, build.md §3). The test JVM has no Vault or LuckPerms on its
 * classpath, which is the server-without-the-plugin case: an absent plugin registers nothing, and a
 * plugin that is reported enabled but whose API cannot load is reported as {@code MTR-0058} and
 * skipped, never a failed enable.
 */
class IntegrationsGuardTest {

    /** Records what the gate registers; no slot semantics are needed here (those are plugin tests). */
    static final class RecordingProviders implements LogicProviders {
        final List<String> set = new ArrayList<>();

        @Override
        public Optional<EconomyProvider> economy() {
            return Optional.empty();
        }

        @Override
        public PermissionProvider permissions() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ServerTransfer> transfer() {
            return Optional.empty();
        }

        @Override
        public ProviderRegistration setEconomy(Plugin owner, EconomyProvider provider) {
            set.add("economy");
            return () -> { };
        }

        @Override
        public ProviderRegistration setPermissions(Plugin owner, PermissionProvider provider) {
            set.add("permissions:" + provider.name());
            return () -> { };
        }

        @Override
        public ProviderRegistration setTransfer(Plugin owner, ServerTransfer provider) {
            set.add("transfer:" + provider.name());
            return () -> { };
        }
    }

    /** A host whose plugin manager reports exactly {@code enabled} as enabled, and which records messenger calls. */
    private static final class Host {
        final Set<String> enabled;
        final List<String> channels = new ArrayList<>();
        final Plugin plugin;

        Host(Set<String> enabled) {
            this.enabled = enabled;
            PluginManager manager = proxy(PluginManager.class, (method, args) -> switch (method.getName()) {
                case "isPluginEnabled" -> enabled.contains(args[0]);
                case "registerEvents" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Messenger messenger = proxy(Messenger.class, (method, args) -> switch (method.getName()) {
                case "isOutgoingChannelRegistered" -> false;
                case "registerOutgoingPluginChannel" -> {
                    channels.add((String) args[1]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Server server = proxy(Server.class, (method, args) -> switch (method.getName()) {
                case "getPluginManager" -> manager;
                case "getMessenger" -> messenger;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            plugin = proxy(Plugin.class, (method, args) -> switch (method.getName()) {
                case "getServer" -> server;
                case "getName" -> "Materione";
                case "getLogger" -> Logger.getLogger("test");
                case "isEnabled" -> true;
                case "hashCode" -> System.identityHashCode(this);
                case "toString" -> "host";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }
    }

    /** Records every diagnostic published. */
    private static final class Diagnostics implements DiagnosticsBus {
        final List<Diagnostic> published = new ArrayList<>();

        @Override
        public void publish(Diagnostic diagnostic) {
            published.add(diagnostic);
        }

        @Override
        public AutoCloseable subscribe(Consumer<Diagnostic> listener) {
            return () -> { };
        }

        @Override
        public List<Diagnostic> recent(int max) {
            return List.copyOf(published);
        }

        @Override
        public int count(Severity severity) {
            return (int) published.stream().filter(d -> d.severity() == severity).count();
        }
    }

    @Test
    void withNoOptionalPluginsNothingIsRegisteredAndNothingIsLoaded() {
        var host = new Host(Set.of());
        var providers = new RecordingProviders();
        var diagnostics = new Diagnostics();

        new Integrations(host.plugin, providers, diagnostics, false).refresh();

        assertThat(providers.set).isEmpty();
        assertThat(diagnostics.published).isEmpty();
        assertThat(host.channels).isEmpty();
    }

    @Test
    void anEnabledVaultWhoseApiCannotLoadIsReportedAndSkippedNotFatal() {
        var host = new Host(Set.of(Integrations.VAULT));
        var providers = new RecordingProviders();
        var diagnostics = new Diagnostics();

        new Integrations(host.plugin, providers, diagnostics, false).refresh();

        assertThat(providers.set).isEmpty();
        assertThat(diagnostics.published).isNotEmpty()
                .allSatisfy(diagnostic -> assertThat(diagnostic.code()).isEqualTo(DiagnosticCodes.INTEGRATION_SKIPPED));
    }

    @Test
    void anEnabledLuckPermsWhoseApiCannotLoadIsReportedAndSkippedNotFatal() {
        var host = new Host(Set.of(Integrations.LUCKPERMS));
        var providers = new RecordingProviders();
        var diagnostics = new Diagnostics();

        new Integrations(host.plugin, providers, diagnostics, false).refresh();

        assertThat(providers.set).isEmpty();
        assertThat(diagnostics.published).hasSize(1);
        assertThat(diagnostics.published.getFirst().code()).isEqualTo(DiagnosticCodes.INTEGRATION_SKIPPED);
    }

    @Test
    void theBungeeCordChannelNeedsNoProxyPluginOnlyTheSetting() {
        var host = new Host(Set.of());
        var providers = new RecordingProviders();

        new Integrations(host.plugin, providers, new Diagnostics(), true).refresh();

        assertThat(providers.set).containsExactly("transfer:BungeeCord plugin channel");
        assertThat(host.channels).containsExactly("BungeeCord");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<java.lang.reflect.Method, Object[], Object> answers) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> type.getSimpleName();
                        };
                    }
                    return answers.apply(method, args);
                }));
    }

}
