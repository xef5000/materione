package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.content.AssetConvention;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.registry.BehaviorVocabulary.StepKind;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.rule.RuleParser;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The engine's closed grammar describes itself as data, and the compiler reads the same table (issue 355). */
class StepKindsGrammarTest {

    @Test
    void theControlKeysAreTheKindsWrittenByTheirOwnKey() {
        assertThat(StepKinds.controlKeys()).containsExactlyInAnyOrder("if", "require", "choose", "wait", "stop", "call");
        assertThat(StepKinds.companions().get("if")).containsExactlyInAnyOrder("then", "else");
        assertThat(StepKinds.companions().get("call")).containsExactly("with");
        assertThat(StepKinds.companions().get("stop")).isEmpty();
    }

    @Test
    void everyFormNamesOnlyParametersItDeclares() {
        for (StepKind form : Stream.concat(StepKinds.all().stream(), StepKinds.conditions().stream()).toList()) {
            Set<String> declared = Set.copyOf(form.parameters().stream().map(ParameterSchema::id).toList());
            if (!form.payload().isEmpty()) {
                assertThat(declared).as(form.id() + " payload").contains(form.payload());
            }
            assertThat(declared).as(form.id() + " siblings").containsAll(form.siblings());
            assertThat(form.siblings()).as(form.id() + " oneOf").containsAll(form.oneOf());
        }
    }

    @Test
    void stepListsConditionsAndTheCooldownScopeAreDeclared() {
        StepKind choose = StepKinds.all().stream().filter(kind -> kind.id().equals("choose")).findFirst().orElseThrow();
        ParameterSchema branches = choose.parameters().get(0);
        assertThat(branches.type()).isEqualTo(ParameterType.LIST);
        assertThat(branches.nested()).extracting(schema -> schema.semantics().valueKind())
                .containsExactly(ParameterSchema.KIND_CONDITION, ParameterSchema.KIND_STEPS);
        ParameterSchema onCooldown = RuleParser.RULE_OPTIONS.stream()
                .filter(option -> option.id().equals("on_cooldown")).findFirst().orElseThrow();
        assertThat(onCooldown.semantics().valueKind()).isEqualTo(ParameterSchema.KIND_STEPS);
        assertThat(onCooldown.semantics().scope()).extracting(variable -> variable.path())
                .containsExactly("cooldown.remaining");
        assertThat(RuleParser.RULE_BODY).extracting(ParameterSchema::id).containsExactly("if", "do");
        assertThat(StepKinds.grammar().conditions()).extracting(StepKind::id)
                .containsExactly("all", "any", "not", "at_least");
    }

    @Test
    void everyTriggerFilterIsDeclaredToTakeAList() {
        var description = new TriggerDescription(SelfDescription.of("T", "t"), List.of(), List.of("gui"),
                List.of(ParameterSchema.of("slot", ParameterType.INTEGER, false)), List.of(), false);
        assertThat(description.filters()).allMatch(filter -> filter.semantics().multiple());
    }

    @Test
    void anAssetIsReferencedRelativeToItsKindDirectoryWithoutItsExtension() {
        assertThat(AssetConvention.reference("assets/textures/item/ruby.png")).isEqualTo("item/ruby");
        assertThat(AssetConvention.reference("models/crate.json")).isEqualTo("crate");
        assertThat(AssetConvention.reference("other/readme")).isEqualTo("other/readme");
    }
}
