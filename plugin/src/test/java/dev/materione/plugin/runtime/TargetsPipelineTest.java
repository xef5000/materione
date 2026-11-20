package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.logic.TargetKind;
import dev.materione.api.logic.TargetSpec;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.behavior.flow.ActionStep;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.core.diagnostics.DiagnosticsBusService;
import dev.materione.core.diagnostics.QuarantineTracker;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.pipeline.ContentPack;
import dev.materione.core.pipeline.ContentResource;
import dev.materione.core.pipeline.PackManifest;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.runtime.behavior.WorldRules;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Target addressing from files to the live shadow (logic.md §39, D6): the {@code targets} fixture pack builds with no
 * diagnostics at all, each rule's step holds the target the file wrote (parsed once, at load, with the kinds the action
 * admits), a rule whose trigger carries no entity is refused when it asks for {@code event.target}, and a document with a
 * bad target loses that step only. Reading the file is the same code as {@code PaperTargetAddressingIT}'s server.
 */
class TargetsPipelineTest {

    private static final Path PACK = GoldenFiles.fixturesRoot().resolve("packs").resolve("targets");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    TargetsPipelineTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "husk", "skeleton", "player"));
        pipeline.registerBuiltins();
    }

    private WorldRules shadowOf(String yaml) {
        var document = LosslessYaml.parse(yaml);
        var resource = new ContentResource(NamespacedId.of("targets", "targets"), "rule", Path.of("targets.yml"), document);
        var manifest = new PackManifest("targets", "1", 1, List.of(), LosslessYaml.parse("id: targets\n"));
        return pipeline.buildShadow(List.of(new ContentPack("targets", Path.of("targets"), manifest, List.of(resource))))
                .view(WorldRules.KEY);
    }

    @Test
    void theFixturePackBuildsWithNoDiagnosticsAndEachStepHoldsItsTarget() throws IOException {
        WorldRules world = shadowOf(Files.readString(PACK.resolve("rules/targets.yml")));

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        var hits = world.forTrigger(EventTypes.ENTITY_HIT);
        assertThat(hits).hasSize(2);

        var bleed = (ActionStep<?>) hits.get(0).steps().get(0);
        assertThat(bleed.id()).isEqualTo("potion");
        assertThat(bleed.target().spec()).isEqualTo(new TargetSpec.Event());
        assertThat(bleed.target().admits()).containsExactlyInAnyOrder(TargetKind.PLAYER, TargetKind.ENTITY);

        var slow = (ActionStep<?>) ((dev.materione.behavior.flow.RequireStep) hits.get(1).steps().get(0)).steps().get(0);
        assertThat(slow.target().spec()).isEqualTo(new TargetSpec.Nearby(8, "zombie", 1));

        // the flow's message asks for an argument: only a player may receive it
        assertThat(world.forTrigger(EventTypes.PLAYER_JOIN)).extracting(rule -> rule.id()).containsExactly(
                "alice_writes_to_nobody", "bob_writes_to_alice", "probe_tells_probe", "probe_tells_nobody");
    }

    @Test
    void anEventTargetOnATriggerThatCarriesNoEntityIsRefusedAtLoadAndOnlyThatStepIsLost() {
        WorldRules world = shadowOf("""
                targets:
                  behavior:
                    - id: join
                      on: player.join
                      do:
                        - potion: {type: speed, target: event.target}
                        - message: {text: hi, target: {player: Notch}}
                """);

        assertThat(diagnostics.recent(64)).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().code()).isEqualTo("MTR-0057");
            assertThat(diagnostic.message()).contains("event.target needs a trigger that carries an entity");
        });
        var steps = world.forTrigger(EventTypes.PLAYER_JOIN).getFirst().steps();
        assertThat(steps).hasSize(1);
        assertThat(((ActionStep<?>) steps.get(0)).id()).isEqualTo("message");
    }

    @Test
    void theHitTriggerCarriesAnEntityAndSoDoesTheKill() {
        shadowOf("""
                targets:
                  behavior:
                    - id: hit
                      on: entity.hit
                      do:
                        - potion: {type: speed, target: event.target}
                    - id: kill
                      on: entity.kill
                      do:
                        - message: {text: bye, target: event.target}
                """);

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
    }

    @Test
    void aMessageThatStillWritesTheOldChannelIsSkippedWithTheReplacementNamed() {
        shadowOf("""
                targets:
                  behavior:
                    - id: shout
                      on: player.join
                      do:
                        - message: {text: hi, target: broadcast}
                        - actionbar: ok
                """);

        assertThat(diagnostics.recent(64)).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.message()).contains("'broadcast' was a message channel"));
    }
}
