package dev.materione.plugin.runtime.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.TriggerType;
import dev.materione.behavior.rule.EventTypes;
import dev.materione.behavior.rule.Rule;
import dev.materione.behavior.logic.BuiltinTriggers;
import dev.materione.content.rules.RuleDefinition;
import dev.materione.content.rules.RuleType;
import dev.materione.core.document.LosslessYaml;
import dev.materione.core.registry.RegistryService;
import dev.materione.plugin.logic.BuiltinLogic;
import dev.materione.plugin.logic.LogicService;
import dev.materione.plugin.logic.LogicTestSupport;
import dev.materione.plugin.logic.support.ItemCatalog;
import dev.materione.plugin.logic.trigger.WorldTriggers;
import dev.materione.testing.golden.GoldenFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A rule document through the real flow compiler and the real trigger registry (logic.md §37): the golden
 * fixtures of {@code fixtures/rules/} load with no problems, each broken rule of {@code bad-rule.yml} is skipped
 * alone with {@code MTR-0021} (the unknown entity type the issue names, an unknown cause, an unknown trigger) and
 * its healthy sibling loads, a trigger no rule document fires is a warning, and a context path the trigger does not
 * provide is reported like on any host.
 */
class RuleDocumentsTest {

    private static final List<String> TYPES = List.of("zombie", "husk", "skeleton", "player");
    private static final List<String> CAUSES = List.of("entity_attack", "projectile", "fall");

    private final RegistryService registries = new RegistryService();
    private final LogicService service = LogicTestSupport.newService(registries);
    private final RuleType type = new RuleType();

    RuleDocumentsTest() {
        var owner = LogicTestSupport.owner("materione");
        for (TriggerType trigger : BuiltinTriggers.all()) {
            service.triggers().register(owner, NamespacedId.of("materione", trigger.id()), trigger);
        }
        for (TriggerType trigger : WorldTriggers.all(TYPES, CAUSES)) {
            service.triggers().register(owner, NamespacedId.of("materione", trigger.id()), trigger);
        }
        BuiltinLogic.actions(ItemCatalog.none()).forEach(action ->
                service.actions().register(owner, NamespacedId.of("materione", action.id()), action));
    }

    @SuppressWarnings("unchecked")
    private RuleDefinition load(String file, String key) throws IOException {
        String raw = Files.readString(GoldenFiles.fixturesRoot().resolve("rules").resolve(file));
        var definition = (Map<String, Object>) LosslessYaml.parse(raw).data().get(key);
        return (RuleDefinition) type.parse(NamespacedId.of("fixture", key), definition, Map.of()).orElseThrow();
    }

    private List<Diagnostic> compile(RuleDefinition document, List<Rule> rules) {
        var problems = new ArrayList<Diagnostic>();
        rules.addAll(RuleDocuments.compile(document, service.lookup(), problems::add));
        return problems;
    }

    @Test
    void theGoldenDocumentLoadsWithNoProblemsAndItsFlowIsLinked() throws IOException {
        var rules = new ArrayList<Rule>();
        var problems = compile(load("welcome.yml", "welcome"), rules);

        assertThat(problems).isEmpty();
        assertThat(rules).extracting(rule -> rule.on().type())
                .containsExactly(EventTypes.PLAYER_JOIN, EventTypes.ENTITY_KILL);
        assertThat(rules.get(1).filters().get("type")).contains(List.of("zombie", "husk"));
    }

    @Test
    void eachBrokenRuleIsSkippedAloneWithMtr0021AndTheHealthySiblingLoads() throws IOException {
        var rules = new ArrayList<Rule>();
        var problems = compile(load("bad-rule.yml", "bad_rules"), rules);

        assertThat(rules).extracting(Rule::id).containsExactly("fine");
        assertThat(problems).hasSize(3).allSatisfy(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID);
            assertThat(problem.severity()).isEqualTo(Severity.WARN);
        });
        var messages = problems.stream().map(Diagnostic::message).toList();
        assertThat(messages).anyMatch(message -> message.contains("unknown type 'zombi'"));
        assertThat(messages).anyMatch(message -> message.contains("unknown cause 'stubbed_toe'"));
        assertThat(messages).anyMatch(message -> message.contains("unknown event type 'player.sneeze'"));
        // the diagnostic names the field, so the editor opens it
        assertThat(problems).extracting(problem -> String.valueOf(problem.target()))
                .anyMatch(target -> target.contains("type"));
    }

    @Test
    void aTriggerNoRuleDocumentFiresLoadsAndIsReportedAsNeverRunning() {
        var document = (RuleDefinition) type.parse(NamespacedId.of("fixture", "clicky"), Map.of(
                "behavior", List.of(Map.of("on", "click:right", "do", List.of(Map.of("message", "hi"))))),
                Map.of()).orElseThrow();
        var rules = new ArrayList<Rule>();
        var problems = compile(document, rules);

        assertThat(rules).hasSize(1);
        assertThat(problems).singleElement().satisfies(problem -> {
            assertThat(problem.code()).isEqualTo(DiagnosticCodes.BEHAVIOR_RULE_INVALID);
            assertThat(problem.message()).contains("not fired for rule documents").contains("click");
        });
    }

    @Test
    void aPathTheTriggerDoesNotProvideIsReportedLikeOnAnyHost() {
        var document = (RuleDefinition) type.parse(NamespacedId.of("fixture", "paths"), Map.of(
                "behavior", List.of(
                        Map.of("on", "player.join", "do", List.of(Map.of("message", "${target.type}"))),
                        Map.of("on", "entity.kill", "do", List.of(Map.of("message", "${target.type} ${damage.final}"))))),
                Map.of()).orElseThrow();
        var problems = compile(document, new ArrayList<>());

        // player.join has no target; entity.kill has a target but no damage
        assertThat(problems).hasSize(2);
        assertThat(problems).extracting(Diagnostic::message)
                .anyMatch(message -> message.contains("target")).anyMatch(message -> message.contains("damage"));
    }

    @Test
    void theWorldRulesViewIndexesTheDocumentsRulesByTriggerInDocumentOrder() throws IOException {
        var welcome = new ArrayList<Rule>();
        compile(load("welcome.yml", "welcome"), welcome);
        var bad = new ArrayList<Rule>();
        compile(load("bad-rule.yml", "bad_rules"), bad);
        var view = WorldRules.of(Map.of(NamespacedId.of("fixture", "welcome"), welcome,
                NamespacedId.of("fixture", "bad_rules"), bad), Map.of());

        assertThat(view.forTrigger(EventTypes.ENTITY_KILL)).extracting(Rule::id)
                .containsExactlyInAnyOrder(welcome.get(1).id(), "fine");
        assertThat(view.forTrigger(EventTypes.PLAYER_JOIN)).hasSize(1);
        assertThat(view.forTrigger(EventTypes.ENTITY_HIT)).isEmpty();
        assertThat(view.subscribes(EventTypes.PLAYER_JOIN)).isTrue();
        assertThat(view.subscribes(EventTypes.ENTITY_HIT)).isFalse();
        assertThat(view.documentCount()).isEqualTo(2);
    }

    @Test
    void anotherHostsRulesSubscribeATriggerToo() throws IOException {
        var itemRules = new ArrayList<Rule>();
        var item = (RuleDefinition) type.parse(NamespacedId.of("fixture", "blade"), Map.of(
                "behavior", List.of(Map.of("on", "entity.hit", "do", List.of(Map.of("message", "hit"))))),
                Map.of()).orElseThrow();
        itemRules.addAll(RuleDocuments.compile(item, service.lookup(), diagnostic -> { }));
        var view = WorldRules.of(Map.of(), Map.of(NamespacedId.of("fixture", "blade"), itemRules));

        assertThat(view.subscribes(EventTypes.ENTITY_HIT)).isTrue();
        assertThat(view.itemsAnswer(EventTypes.ENTITY_HIT)).isTrue();
        assertThat(view.itemsAnswer(EventTypes.ENTITY_KILL)).isFalse();
        assertThat(view.forTrigger(EventTypes.ENTITY_HIT)).isEmpty(); // only rule documents are indexed for dispatch
    }
}
