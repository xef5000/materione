package dev.materione.behavior.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerDescription;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.rule.EventSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** How an author's spelling of an action, a trigger or an id reaches the registered type. */
class LogicLookupTest {

    private final LogicFixture logic = new LogicFixture()
            .action(LogicFixture.recording("take", Threading.GLOBAL, new ArrayList<>()))
            .action("example", LogicFixture.recording("greet", Threading.ANY, new ArrayList<>()));

    @Test
    void builtInsAreWrittenBareOrQualifiedAndAddonsOnlyQualified() {
        var lookup = logic.lookup();

        assertThat(lookup.action("take")).isPresent();
        assertThat(lookup.action("materione:take")).isPresent();
        assertThat(lookup.action("example:greet")).isPresent();
        assertThat(lookup.action("greet")).isEmpty(); // an addon never shadows a built-in's spelling
        assertThat(lookup.action("Take Me")).isEmpty();
        assertThat(lookup.action(null)).isEmpty();
        assertThat(lookup.condition("take")).isEmpty(); // actions and conditions are separate registries
    }

    @Test
    void idsDisplayBareForMaterioneAndQualifiedForEveryoneElse() {
        assertThat(LogicIds.display(NamespacedId.of("materione", "gui.open"))).isEqualTo("gui.open");
        assertThat(LogicIds.display(NamespacedId.of("example", "greet"))).isEqualTo("example:greet");
        assertThat(LogicIds.parse("gui.open")).contains(NamespacedId.of("materione", "gui.open"));
        assertThat(LogicIds.parse("a:b:c")).isEmpty();
    }

    @Test
    void aSelectorSplitsTheDetailOffABuiltInTriggerAtTheFirstColon() {
        var lookup = logic.lookup();

        assertThat(lookup.selector("click:right")).contains(new EventSelector("click", Optional.of("right")));
        assertThat(lookup.selector("  CLICK  ")).contains(new EventSelector("click", Optional.empty()));
        assertThat(lookup.selector("menu.click")).contains(new EventSelector("menu.click", Optional.empty()));
        assertThat(lookup.selector("click:Right")).contains(new EventSelector("click", Optional.of("Right")));
        assertThat(lookup.selector("click:")).contains(new EventSelector("click", Optional.empty()));
        assertThat(lookup.selector("clik")).isEmpty();
        assertThat(lookup.selector("")).isEmpty();
    }

    @Test
    void anAddonTriggerIsQualifiedAndAFirstSegmentThatIsABuiltInIsNeverANamespace() {
        trigger("example", "storm");
        trigger("click", "weird"); // a namespace that spells a built-in trigger
        var lookup = logic.lookup();

        assertThat(lookup.selector("example:storm")).contains(new EventSelector("example:storm", Optional.empty()));
        assertThat(lookup.selector("example:storm:heavy"))
                .contains(new EventSelector("example:storm", Optional.of("heavy")));
        assertThat(lookup.selector("example:nothing")).isEmpty();
        // click:weird is trigger click with detail "weird", not the addon's click:weird
        assertThat(lookup.selector("click:weird")).contains(new EventSelector("click", Optional.of("weird")));
        assertThat(lookup.selector("materione:click:left")).contains(new EventSelector("click", Optional.of("left")));
    }

    private void trigger(String namespace, String name) {
        logic.registries().registry(RegistryKeys.TRIGGERS, TriggerType.class).register(
                NamespacedId.of(namespace, name), new TriggerType() {
                    @Override
                    public String id() {
                        return name;
                    }

                    @Override
                    public TriggerDescription describe() {
                        return new TriggerDescription(SelfDescription.of(name, "test"),
                                List.of(), List.of(), List.of(), List.of(), false);
                    }
                }, SelfDescription.of(name, "test"), namespace);
    }
}
