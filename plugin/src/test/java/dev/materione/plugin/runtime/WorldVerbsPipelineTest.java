package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
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
import dev.materione.core.runtime.RuntimeRegistries;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.runtime.behavior.ItemBehaviors;
import dev.materione.plugin.runtime.behavior.WorldRules;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The entity and world verbs from files to the live shadow (logic.md §41, D6: one pipeline): the {@code worldverbs}
 * fixture pack uses all nine with no diagnostics at all; a verb that asks for what its trigger cannot give is told so at
 * load (a location, an item stack, a target), and a step that is wrong loses only itself. The same files are read by
 * {@code PaperWorldVerbsIT}'s server.
 */
class WorldVerbsPipelineTest {

    private static final Path PACK = GoldenFiles.fixturesRoot().resolve("packs").resolve("worldverbs");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    WorldVerbsPipelineTest() {
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "husk", "player"));
        pipeline.registerBuiltins();
    }

    private static ContentResource file(String relative, String type, String id) throws IOException {
        Path file = PACK.resolve(relative);
        return text(Files.readString(file), file, type, id);
    }

    private static ContentResource text(String yaml, Path file, String type, String id) {
        return new ContentResource(NamespacedId.of("worldverbs", id), type, file, LosslessYaml.parse(yaml));
    }

    private RuntimeRegistries shadow(List<ContentResource> resources) {
        var manifest = new PackManifest("worldverbs", "1", 1, List.of(), LosslessYaml.parse("id: worldverbs\n"));
        return pipeline.buildShadow(List.of(new ContentPack("worldverbs", Path.of("worldverbs"), manifest, resources)));
    }

    private List<ContentResource> fixturePack() throws IOException {
        return List.of(
                file("items/excavator.yml", "item", "excavator"),
                file("items/dash_blade.yml", "item", "dash_blade"),
                file("items/leech_dagger.yml", "item", "leech_dagger"),
                file("items/fire_wand.yml", "item", "fire_wand"),
                file("items/builder_wand.yml", "item", "builder_wand"),
                file("rules/verbs.yml", "rule", "verbs"));
    }

    private static List<String> actionIds(List<? extends dev.materione.behavior.flow.Step> steps) {
        var ids = new ArrayList<String>();
        for (var step : steps) {
            if (step instanceof ActionStep<?> action) {
                ids.add(action.id());
            }
        }
        return ids;
    }

    @Test
    void theFixturePackUsesAllNineVerbsAndLoadsWithNoDiagnostics() throws IOException {
        RuntimeRegistries shadow = shadow(fixturePack());

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        var items = shadow.view(ItemBehaviors.KEY);
        var used = new java.util.TreeSet<String>();
        for (String item : List.of("excavator", "dash_blade", "leech_dagger", "fire_wand", "builder_wand")) {
            for (var rule : items.forItem(NamespacedId.of("worldverbs", item)).orElseThrow()) {
                used.addAll(actionIds(rule.steps()));
            }
        }
        shadow.view(WorldRules.KEY).forTrigger(EventTypes.PLAYER_JOIN)
                .forEach(rule -> used.addAll(actionIds(rule.steps())));
        shadow.view(WorldRules.KEY).forTrigger(EventTypes.ENTITY_HIT)
                .forEach(rule -> used.addAll(actionIds(rule.steps())));

        assertThat(used).contains("heal", "damage", "velocity", "particle", "launch", "break_blocks", "set_block",
                "drop_item", "stack.edit");
    }

    @Test
    void theHealOnTheStruckEntityHoldsItsTarget() throws IOException {
        RuntimeRegistries shadow = shadow(fixturePack());

        var rule = shadow.view(WorldRules.KEY).forTrigger(EventTypes.ENTITY_HIT).getFirst();
        var heal = (ActionStep<?>) rule.steps().get(0);

        assertThat(heal.id()).isEqualTo("heal");
        assertThat(heal.target().spec()).isEqualTo(new dev.materione.api.logic.TargetSpec.Event());
    }

    @Test
    void anActionThatHasNoTargetRejectsOneAndOnlyThatStepIsLost() {
        shadow(List.of(text("""
                verbs:
                  behavior:
                    - id: join
                      on: player.join
                      do:
                        - break_blocks: {target: event.target}
                        - heal: 4
                """, Path.of("rules/verbs.yml"), "rule", "verbs")));

        assertThat(diagnostics.recent(64)).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().code()).isEqualTo("MTR-0057");
            assertThat(diagnostic.message()).contains("break_blocks");
        });
    }

    @Test
    void aShapePastTheCapAndAnUnknownParticleNameAreRefusedAtLoad() {
        var shadow = shadow(List.of(text("""
                verbs:
                  behavior:
                    - id: join
                      on: player.join
                      do:
                        - break_blocks: {shape: 9}
                        - particle: {particle: "Bad Name"}
                        - launch: {projectile: arrow, count: 99}
                        - velocity: {mode: add}
                        - heal: 1
                """, Path.of("rules/verbs.yml"), "rule", "verbs")));

        assertThat(diagnostics.recent(64)).hasSize(4).allSatisfy(diagnostic ->
                assertThat(diagnostic.code().code()).isEqualTo("MTR-0057"));
        assertThat(actionIds(shadow.view(WorldRules.KEY).forTrigger(EventTypes.PLAYER_JOIN).getFirst().steps()))
                .containsExactly("heal");
    }

    @Test
    void aStackEditOnATriggerThatIsNotAboutAnItemIsToldSoAtLoad() {
        var shadow = shadow(List.of(text("""
                verbs:
                  behavior:
                    - id: join
                      on: player.join
                      do:
                        - stack.edit: {name: "x"}
                """, Path.of("rules/verbs.yml"), "rule", "verbs")));

        assertThat(diagnostics.recent(64)).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code().code()).isEqualTo("MTR-0060");
            assertThat(diagnostic.message()).contains("'item'").contains("stack.edit needs a trigger");
        });
        // a warning: the rule still loads and fails soft when it runs
        assertThat(shadow.view(WorldRules.KEY).forTrigger(EventTypes.PLAYER_JOIN)).hasSize(1);
    }

    @Test
    void theEventLocationIsReadFromTheTriggerSoATriggerWithoutOneIsToldSoAtLoad() {
        // view.open carries no location
        shadow(List.of(text("""
                verbs:
                  behavior:
                    - id: opened
                      on: gui.open
                      do:
                        - particle: {particle: cloud, at: event}
                """, Path.of("rules/verbs.yml"), "rule", "verbs")));

        assertThat(diagnostics.recent(64)).extracting(diagnostic -> diagnostic.code().code()).contains("MTR-0060");
    }
}
