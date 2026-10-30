package dev.materione.behavior.flow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.TemplateText;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.logic.LogicFixture;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.Yaml;

/**
 * What the flow tests compile against: the real registries and triggers, a handful of recording
 * fake actions ({@code a}, {@code b}, {@code c} on the player's thread, {@code g} global,
 * {@code pure} thread-free, {@code fails}, {@code boom}, {@code cancel}) and fake predicates
 * ({@code pass}, {@code deny}, {@code boomy}). Rules are written as YAML, the shape the pipeline
 * hands the compiler.
 */
public final class FlowFixture {

    public static final NamespacedId OWNER = NamespacedId.of("sample", "shop");

    /** Every action that ran, as {@code id:value}. */
    public final List<String> ran = new ArrayList<>();
    /** Every predicate that was asked, as {@code id:label}. */
    public final List<String> asked = new ArrayList<>();
    public final LogicFixture logic = new LogicFixture();

    public FlowFixture() {
        for (String id : List.of("a", "b", "c", "fails", "boom", "message")) {
            logic.action(LogicFixture.recording(id, Threading.PLAYER_REGION, ran));
        }
        logic.action(LogicFixture.recording("g", Threading.GLOBAL, ran));
        logic.action(LogicFixture.recording("pure", Threading.ANY, ran));
        logic.action(new LogicFixture.CancelFake());
        logic.action(new Say(ran));
        logic.condition(LogicFixture.predicate("pass", true, asked));
        logic.condition(LogicFixture.predicate("deny", false, asked));
        logic.condition(LogicFixture.predicate("boomy", true, asked));
    }

    /** {@code say: "text ${args.x}"}: renders its template against the run's context and records {@code say:<text>}. */
    public static final class Say implements ActionType<TemplateText> {
        private final List<String> ran;

        Say(List<String> ran) {
            this.ran = ran;
        }

        @Override
        public String id() {
            return "say";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Say", "Records a rendered template",
                    List.of(ParameterSchema.of("text", ParameterType.STRING, true)), "1.0.0"),
                    LogicCategory.PLAYER, "text");
        }

        @Override
        public Threading threading() {
            return Threading.PLAYER_REGION;
        }

        @Override
        public TemplateText parse(ParamReader params) {
            return params.template("text");
        }

        @Override
        public ActionOutcome execute(TemplateText params, LogicContext context) {
            ran.add("say:" + params.render(context));
            return ActionOutcome.OK;
        }
    }

    public static Object yaml(String text) {
        // YAML 1.1 reads a bare "on" key as a boolean; the pipeline's loader does not, so quote it here
        return new Yaml().load(text.replaceAll("(?m)^(\\s*(?:- )?)on:", "$1\"on\":"));
    }

    /** Compiles {@code behavior:} alone. */
    public ParsedRules compile(String behavior) {
        return FlowCompiler.compile(OWNER, yaml(behavior), logic.lookup());
    }

    /** Compiles {@code behavior:} with {@code flows:}. */
    public ParsedRules compile(String behavior, String flows) {
        return FlowCompiler.compile(OWNER, yaml(behavior), yaml(flows), logic.lookup());
    }

    /** The one rule {@code behavior} declares, asserting it compiled without a single problem. */
    public Rule clean(String behavior) {
        ParsedRules parsed = compile(behavior);
        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
        return parsed.rules().get(0);
    }

    /** The one rule, with flows, asserting no problems. */
    public Rule clean(String behavior, String flows) {
        ParsedRules parsed = compile(behavior, flows);
        assertThat(parsed.problems()).isEmpty();
        assertThat(parsed.rules()).hasSize(1);
        return parsed.rules().get(0);
    }

    /** Wraps steps in a single click rule: {@code steps} is the body of {@code do:}, indented by the caller. */
    public static String rule(String doBody) {
        return "- on: click\n  do:\n" + doBody;
    }
}

