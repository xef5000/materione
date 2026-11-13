package dev.materione.plugin.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.platform.PlatformDescriptor;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.behavior.rule.Rule;
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
 * Rule documents from files to the live shadow (logic.md §37, D6: one pipeline): the {@code worldrules} fixture
 * pack builds a shadow whose {@code WorldRules} view answers the world triggers with no diagnostics at all, an item
 * in the same pack holds its own {@code entity.hit} rule, and the fault fixtures cost only what they should: a
 * broken rule is skipped alone, a document that is not a rule document leaves the shadow without it.
 */
class WorldRulesPipelineTest {

    private static final Path PACK = GoldenFiles.fixturesRoot().resolve("packs").resolve("worldrules");
    private static final Path FIXTURES = GoldenFiles.fixturesRoot().resolve("rules");

    private final RegistryService registries = new RegistryService();
    private final DiagnosticsBusService diagnostics = new DiagnosticsBusService(64);
    private final PipelineService pipeline = new PipelineService(null, diagnostics, new QuarantineTracker(),
            registries, null, new PlatformDescriptor("1.21.4", false, false, false, Set.of()));

    WorldRulesPipelineTest() {
        // the logic registries rules resolve against; the server's entity types stand in as a fixed list
        LogicTestSupport.withBuiltIns(registries, List.of("zombie", "husk", "skeleton", "player"));
        pipeline.registerBuiltins();
    }

    private static ContentResource resource(Path file, String type, String id) throws IOException {
        var document = LosslessYaml.parse(Files.readString(file));
        return new ContentResource(NamespacedId.of("worldrules", id), type, file, document);
    }

    private RuntimeRegistries shadow(List<ContentResource> resources) {
        var manifest = new PackManifest("worldrules", "1", 1, List.of(), LosslessYaml.parse("id: worldrules\n"));
        return pipeline.buildShadow(List.of(new ContentPack("worldrules", Path.of("worldrules"), manifest, resources)));
    }

    @Test
    void theFixturePackBuildsAShadowThatAnswersTheWorldTriggersWithNoDiagnostics() throws IOException {
        var shadow = shadow(List.of(
                resource(PACK.resolve("variables/kills.yml"), "variable", "zombie_kills"),
                resource(PACK.resolve("rules/world.yml"), "rule", "world"),
                resource(PACK.resolve("items/rule_blade.yml"), "item", "rule_blade")));

        assertThat(diagnostics.recent(64)).extracting(Diagnostic::message).isEmpty();
        WorldRules world = shadow.view(WorldRules.KEY);
        assertThat(world.documentCount()).isEqualTo(1);
        for (String trigger : List.of(EventTypes.ENTITY_KILL, EventTypes.ENTITY_HIT, EventTypes.PLAYER_JOIN,
                EventTypes.PLAYER_QUIT, EventTypes.PLAYER_DEATH, EventTypes.PLAYER_RESPAWN, EventTypes.PLAYER_HURT)) {
            assertThat(world.subscribes(trigger)).as(trigger).isTrue();
        }
        assertThat(world.forTrigger(EventTypes.ENTITY_KILL)).extracting(Rule::id).containsExactly("count_zombie_kills");
        // a rule is gated by its filters at fire time: the kill rule answers zombies only, the hit rule zombies and melee
        var kill = world.forTrigger(EventTypes.ENTITY_KILL).getFirst();
        assertThat(kill.filters().get("type")).contains(List.of("zombie"));
        assertThat(world.forTrigger(EventTypes.ENTITY_HIT)).extracting(Rule::id)
                .containsExactly("hit_zombie", "hit_husk_cancelled");
        // the item holds its own rule on the same trigger, and it is not in the document index
        var blade = shadow.view(ItemBehaviors.KEY).forItem(NamespacedId.of("worldrules", "rule_blade")).orElseThrow();
        assertThat(blade).extracting(rule -> rule.on().type()).containsExactly(EventTypes.ENTITY_HIT);
    }

    @Test
    void aShadowWithNoRuleDocumentsStillHasAnEmptyViewSoTheGateNeverNeedsANullCheckOnTheRules() throws IOException {
        var shadow = shadow(List.of(resource(PACK.resolve("variables/kills.yml"), "variable", "zombie_kills")));

        assertThat(shadow.view(WorldRules.KEY).documentCount()).isZero();
        assertThat(shadow.view(WorldRules.KEY).subscribes(EventTypes.ENTITY_KILL)).isFalse();
    }

    @Test
    void aBrokenRuleIsSkippedAloneAndItsDocumentStillLoadsTheOthers() throws IOException {
        var shadow = shadow(List.of(resource(FIXTURES.resolve("bad-rule.yml"), "rule", "bad_rules")));

        var codes = new ArrayList<String>();
        diagnostics.recent(64).forEach(diagnostic -> codes.add(diagnostic.code().code()));
        assertThat(codes).containsExactly("MTR-0021", "MTR-0021", "MTR-0021");
        assertThat(diagnostics.recent(64)).allSatisfy(d -> assertThat(d.code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID));
        assertThat(shadow.view(WorldRules.KEY).forTrigger(EventTypes.ENTITY_KILL)).extracting(Rule::id)
                .containsExactly("fine");
        assertThat(shadow.view(WorldRules.KEY).forTrigger(EventTypes.ENTITY_HIT)).isEmpty();
    }

    @Test
    void aDocumentThatIsNotARuleDocumentLeavesTheShadowWithoutItAndNeverThrows() throws IOException {
        var shadow = shadow(List.of(
                resource(FIXTURES.resolve("bad-behavior.yml"), "rule", "broken"),
                resource(FIXTURES.resolve("bad-behavior.yml"), "rule", "healthy")));

        // each resource is read by its key: the broken one parses to nothing, the healthy one is live
        WorldRules world = shadow.view(WorldRules.KEY);
        assertThat(world.forDocument(NamespacedId.of("worldrules", "broken"))).isEmpty();
        assertThat(world.forDocument(NamespacedId.of("worldrules", "healthy"))).hasSize(1);
        assertThat(world.subscribes(EventTypes.PLAYER_JOIN)).isTrue();
    }
}
