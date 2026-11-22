package dev.materione.plugin.protection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.protection.ProtectionActor;
import dev.materione.api.protection.ProtectionAnswer;
import dev.materione.api.protection.ProtectionApi;
import dev.materione.api.protection.ProtectionMode;
import dev.materione.api.protection.ProtectionPort;
import dev.materione.api.protection.ProtectionQuery;
import dev.materione.api.protection.ProtectionSafety;
import dev.materione.api.registry.SelfDescription;
import dev.materione.plugin.logic.world.Regions;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * The protection service (protection.md sections 3, 5 and 6): how providers' answers combine, the actor-less switch, the
 * thread rules and the hop, the offline actor across a region boundary, the failing provider that is denied and reported
 * once, and the registrations that leave with their plugin.
 */
class ProtectionServiceTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e7");

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.BiFunction<java.lang.reflect.Method, Object[], Object> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    default -> type.getSimpleName();
                };
            }
            return answers.apply(method, args);
        });
    }

    private static final World WORLD = proxy(World.class, (method, args) -> switch (method.getName()) {
        case "getName" -> "world";
        default -> throw new AssertionError(method.getName());
    });

    private static Location at(int x, int y, int z) {
        return new Location(WORLD, x, y, z);
    }

    private static Player player() {
        return proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> ALEX;
            default -> throw new AssertionError(method.getName());
        });
    }

    private static Plugin plugin(String name, boolean enabled) {
        return proxy(Plugin.class, (method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "isEnabled" -> enabled;
            default -> throw new AssertionError(method.getName());
        });
    }

    /** Ownership the test scripts: a location and a player are owned unless flagged. */
    private static final class Ownership implements Regions {
        boolean locationOwned = true;
        boolean playerOwned = true;
        final List<String> hops = new ArrayList<>();

        @Override
        public boolean ownedHere(Location location) {
            return locationOwned;
        }

        @Override
        public boolean ownedHere(Entity entity) {
            return playerOwned;
        }

        @Override
        public <T> CompletionStage<T> at(Location location, Supplier<T> task) {
            if (!locationOwned) {
                hops.add("hop");
                locationOwned = true; // the hop lands on the owning thread
                try {
                    return CompletableFuture.completedFuture(task.get());
                } finally {
                    locationOwned = false;
                }
            }
            return CompletableFuture.completedFuture(task.get());
        }
    }

    /** A provider whose answers a test scripts, and which records who it was asked about. */
    private static final class Scripted implements ProtectionPort {
        final String name;
        ProtectionSafety safety = ProtectionSafety.OWNING_REGION;
        ProtectionAnswer answer;
        RuntimeException failure;
        final List<ProtectionActor> actors = new ArrayList<>();

        Scripted(String name, ProtectionAnswer answer) {
            this.name = name;
            this.answer = answer;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public SelfDescription describe() {
            return SelfDescription.of(name, "scripted " + name);
        }

        @Override
        public ProtectionSafety safety() {
            return safety;
        }

        @Override
        public ProtectionAnswer canBreak(ProtectionActor actor, Location location) {
            actors.add(actor);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private final Ownership regions = new Ownership();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private long now = 1_000_000;

    private ProtectionService service(ProtectionSettings settings, boolean folia) {
        return new ProtectionService(settings, regions, folia, diagnostics::add, () -> now,
                id -> proxy(OfflinePlayer.class, (method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    default -> throw new AssertionError(method.getName());
                }));
    }

    private ProtectionService service() {
        return service(ProtectionSettings.DEFAULTS, false);
    }

    private static ProtectionQuery breaking(ProtectionActor actor) {
        return ProtectionQuery.of(dev.materione.api.protection.ProtectionAction.BREAK, actor, at(1, 64, 1));
    }

    @Test
    void withNoProviderTheAnswerIsAnAbstainAndNothingIsAsked() {
        assertThat(service().ask(breaking(ProtectionActor.of(player()))).verdict())
                .isEqualTo(ProtectionAnswer.Verdict.ABSTAIN);
    }

    @Test
    void aDenyWinsOverAnAllowAndNamesTheDenyingProvider() {
        var service = service();
        service.register(plugin("A", true), new Scripted("Alpha", ProtectionAnswer.allow("Alpha")));
        service.register(plugin("B", true), new Scripted("Beta", ProtectionAnswer.deny("Beta", "no building")));

        ProtectionAnswer answer = service.ask(breaking(ProtectionActor.of(player())));

        assertThat(answer.denied()).isTrue();
        assertThat(answer.provider()).isEqualTo("Beta");
        assertThat(answer.describeDenial()).isEqualTo("refused by Beta (no building)");
    }

    @Test
    void abstainFallsThroughAndAnAllowIsNamed() {
        var service = service();
        service.register(plugin("A", true), new Scripted("Alpha", ProtectionAnswer.abstain("Alpha")));
        service.register(plugin("B", true), new Scripted("Beta", ProtectionAnswer.allow("Beta")));
        service.register(plugin("C", true), new Scripted("Gamma", ProtectionAnswer.allow("Gamma")));

        ProtectionAnswer answer = service.ask(breaking(ProtectionActor.of(player())));

        assertThat(answer.verdict()).isEqualTo(ProtectionAnswer.Verdict.ALLOW);
        assertThat(answer.provider()).isEqualTo("Beta, Gamma");
    }

    @Test
    void everyProviderAbstainingIsAnAbstain() {
        var service = service();
        service.register(plugin("A", true), new Scripted("Alpha", ProtectionAnswer.abstain("Alpha")));

        assertThat(service.ask(breaking(ProtectionActor.of(player()))).verdict())
                .isEqualTo(ProtectionAnswer.Verdict.ABSTAIN);
    }

    @Test
    void theNoActorSwitchAnswersAnActorlessQuestionWithoutAskingAnyone() {
        var provider = new Scripted("Alpha", ProtectionAnswer.deny("Alpha", "claimed"));
        var asking = service();
        asking.register(plugin("A", true), provider);
        assertThat(asking.ask(breaking(ProtectionActor.none())).denied()).isTrue();

        var allowing = service(new ProtectionSettings(ProtectionMode.BOTH, false), false);
        allowing.register(plugin("A", true), provider);
        provider.actors.clear();
        assertThat(allowing.ask(breaking(ProtectionActor.none())).verdict()).isEqualTo(ProtectionAnswer.Verdict.ABSTAIN);
        assertThat(provider.actors).isEmpty();
        // a player is still asked
        assertThat(allowing.ask(breaking(ProtectionActor.of(player()))).denied()).isTrue();
    }

    @Test
    void aProviderThatThrowsIsADenyForThatCallAndReportedOncePerTenSeconds() {
        var service = service();
        var broken = new Scripted("Broken", null);
        broken.failure = new IllegalStateException("claims not loaded");
        service.register(plugin("A", true), broken);

        ProtectionAnswer first = service.ask(breaking(ProtectionActor.of(player())));
        service.ask(breaking(ProtectionActor.of(player())));

        assertThat(first.denied()).isTrue();
        assertThat(first.provider()).isEqualTo("Broken");
        assertThat(diagnostics).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(DiagnosticCodes.PROTECTION_PROVIDER_FAILED);
            assertThat(d.message()).contains("Broken", "claims not loaded");
        });

        now += ProtectionService.REPORT_INTERVAL_MS;
        service.ask(breaking(ProtectionActor.of(player())));
        assertThat(diagnostics).hasSize(2);

        broken.failure = null; // the provider is asked again on the next call: a failure is not remembered
        broken.answer = ProtectionAnswer.allow("Broken");
        assertThat(service.ask(breaking(ProtectionActor.of(player()))).denied()).isFalse();
    }

    @Test
    void aLinkageErrorIsAFailureToo() {
        var service = service();
        service.register(plugin("A", true), new ProtectionPort() {
            @Override public String name() { return "Linked"; }
            @Override public SelfDescription describe() { return SelfDescription.of("Linked", "x"); }
            @Override public ProtectionAnswer canBreak(ProtectionActor actor, Location location) {
                throw new NoClassDefFoundError("com/example/Missing");
            }
        });

        assertThat(service.ask(breaking(ProtectionActor.of(player()))).denied()).isTrue();
        assertThat(diagnostics).hasSize(1);
    }

    @Test
    void aProviderAskedFromAThreadThatDoesNotOwnTheLocationIsDeniedNotRead() {
        var service = service();
        var provider = new Scripted("Alpha", ProtectionAnswer.allow("Alpha"));
        var anywhere = new Scripted("Anywhere", ProtectionAnswer.allow("Anywhere"));
        anywhere.safety = ProtectionSafety.ANY_THREAD;
        service.register(plugin("A", true), provider);
        regions.locationOwned = false;

        assertThat(service.ask(breaking(ProtectionActor.of(player()))).denied()).isTrue();
        assertThat(provider.actors).isEmpty(); // never called on the wrong thread
        assertThat(diagnostics).singleElement()
                .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.PROTECTION_PROVIDER_FAILED));

        var safe = service();
        safe.register(plugin("A", true), anywhere);
        assertThat(safe.ask(breaking(ProtectionActor.of(player()))).verdict()).isEqualTo(ProtectionAnswer.Verdict.ALLOW);
    }

    @Test
    void askAtHopsToTheOwningRegionFirst() {
        var service = service();
        var provider = new Scripted("Alpha", ProtectionAnswer.deny("Alpha", "no"));
        service.register(plugin("A", true), provider);
        regions.locationOwned = false;

        ProtectionAnswer answer = service.askAt(breaking(ProtectionActor.of(player()))).toCompletableFuture().join();

        assertThat(answer.denied()).isTrue();
        assertThat(regions.hops).containsExactly("hop");
        assertThat(provider.actors).hasSize(1); // asked on the owning thread, once
    }

    @Test
    void anActorWhoseThreadIsNotTheAskingOneIsHandedOverAsAnOfflinePlayer() {
        var service = service();
        var provider = new Scripted("Alpha", ProtectionAnswer.allow("Alpha"));
        service.register(plugin("A", true), provider);
        regions.playerOwned = false;

        service.ask(breaking(ProtectionActor.of(player())));

        assertThat(provider.actors).singleElement().satisfies(actor -> {
            assertThat(actor.online()).isEmpty();
            assertThat(actor.id()).contains(ALEX);
        });

        regions.playerOwned = true;
        provider.actors.clear();
        service.ask(breaking(ProtectionActor.of(player())));
        assertThat(provider.actors.getFirst().online()).isPresent();
    }

    @Test
    void aRegistrationClosesAndALeavingPluginTakesItsProvidersWithIt() {
        var service = service();
        Plugin owner = plugin("Owner", true);
        Plugin other = plugin("Other", true);
        ProtectionApi.Registration kept = service.register(other, new Scripted("Kept", ProtectionAnswer.allow("Kept")));
        ProtectionApi.Registration closed = service.register(owner, new Scripted("Closed", ProtectionAnswer.allow("Closed")));
        service.register(owner, new Scripted("Leaving", ProtectionAnswer.allow("Leaving")));
        assertThat(service.providers()).extracting(ProtectionApi.ProviderInfo::name)
                .containsExactly("Kept", "Closed", "Leaving");

        closed.close();
        closed.close(); // idempotent
        assertThat(service.providers()).extracting(ProtectionApi.ProviderInfo::name).containsExactly("Kept", "Leaving");

        service.dropProvidersOf(owner);
        assertThat(service.providers()).extracting(ProtectionApi.ProviderInfo::name).containsExactly("Kept");
        kept.close();
        assertThat(service.providers()).isEmpty();
    }

    @Test
    void aDisabledOwnerCannotRegister() {
        assertThatThrownBy(() -> service().register(plugin("Gone", false), new Scripted("X", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSelfDescriptionListsWhoIsPresentAndWhatTheyDeclared() {
        var service = service();
        var provider = new Scripted("Alpha", ProtectionAnswer.allow("Alpha"));
        provider.safety = ProtectionSafety.ANY_THREAD;
        service.register(plugin("AlphaPlugin", true), provider);

        assertThat(service.providers()).singleElement().satisfies(info -> {
            assertThat(info.name()).isEqualTo("Alpha");
            assertThat(info.owner()).isEqualTo("AlphaPlugin");
            assertThat(info.safety()).isEqualTo(ProtectionSafety.ANY_THREAD);
            assertThat(info.description()).isEqualTo("scripted Alpha");
        });
    }

    @Test
    void onFoliaAMainThreadOnlyProviderIsRefusedAndReported() {
        var service = service(ProtectionSettings.DEFAULTS, true);
        var legacy = new Scripted("Legacy", ProtectionAnswer.allow("Legacy"));
        legacy.safety = ProtectionSafety.MAIN_THREAD_ONLY;

        assertThatThrownBy(() -> service.register(plugin("L", true), legacy)).isInstanceOf(IllegalArgumentException.class);

        assertThat(service.providers()).isEmpty();
        assertThat(diagnostics).singleElement()
                .satisfies(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.PROTECTION_PROVIDER_UNUSABLE));

        // on Paper the same provider is fine
        service(ProtectionSettings.DEFAULTS, false).register(plugin("L", true), legacy);
    }

    @Test
    void theModeAndItsConsequencesFollowTheSettings() {
        assertThat(service(ProtectionSettings.DEFAULTS, false).mode()).isEqualTo(ProtectionMode.BOTH);
        var event = service(ProtectionSettings.EVENT_ONLY, false);
        assertThat(event.asksPort()).isFalse();
        assertThat(event.runsEventPath()).isTrue();
        var port = service(new ProtectionSettings(ProtectionMode.PORT, true), false);
        assertThat(port.asksPort()).isTrue();
        assertThat(port.runsEventPath()).isFalse();
        assertThat(ProtectionService.inert().mode()).isEqualTo(ProtectionMode.EVENT);
    }
}
