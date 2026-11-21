package dev.materione.behavior.run;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.behavior.flow.FlowFixture;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.rule.ParsedRules;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code args} is two things (logic.md §2.4, §40): the arguments a {@code call} passes to the flow it runs, and the typed
 * arguments a host puts in the run's own context (a command document's {@code args.*}). A called flow's own arguments
 * shadow the host's only when the call passed some, so a command's flow that {@code call}s a flow with nothing to pass
 * still reads the command's arguments, and one called {@code with:} reads exactly what it was given.
 */
class FlowRunArgsTest {

    private final FlowFixture f = new FlowFixture();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final FlowInterpreter interpreter = new FlowInterpreter(new ManualScheduler(), RunBudget.DEFAULT, diagnostics::add);

    /** A host whose context carries typed {@code args}, as a command's does. */
    private static MapLogicContext hostWithArgs() {
        var args = new LinkedHashMap<String, Object>();
        args.put("amount", 40);
        args.put("who", "Bob");
        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.of("uuid", "u1"));
        root.put("args", args);
        root.put("vars", new LinkedHashMap<String, Object>());
        return new MapLogicContext(root);
    }

    private Rule rule(String steps, String flows) {
        String body = steps.lines().map(line -> "    " + line).reduce("", (a, b) -> a + b + "\n");
        ParsedRules parsed = flows == null ? f.compile("- on: click\n  do:\n" + body)
                : f.compile("- on: click\n  do:\n" + body, flows);
        return parsed.rules().get(0);
    }

    @Test
    void theRulesOwnStepsReadTheHostsArguments() {
        interpreter.start(rule("""
                - say: "${args.who} pays ${args.amount}"
                - if: "args.amount > 30"
                  then: [ { a: big } ]
                  else: [ { a: small } ]
                """, null), hostWithArgs(), RunOwner.of("u1", null));

        assertThat(f.ran).containsExactly("say:Bob pays 40", "a:big");
    }

    @Test
    void aFlowCalledWithNothingToPassStillReadsTheHostsArguments() {
        interpreter.start(rule("""
                - call: report
                """, """
                report:
                  - say: "report ${args.who} ${args.amount * 2}"
                """), hostWithArgs(), RunOwner.of("u1", null));

        assertThat(f.ran).containsExactly("say:report Bob 80");
    }

    @Test
    void aFlowCalledWithArgumentsReadsOnlyWhatItWasGivenAndTheHostsComeBack() {
        interpreter.start(rule("""
                - call: { flow: report, with: { amount: 5 } }
                - say: "after ${args.who} ${args.amount}"
                """, """
                report:
                  - say: "in [${args.who}] ${args.amount}"
                """), hostWithArgs(), RunOwner.of("u1", null));

        assertThat(f.ran).containsExactly("say:in [] 5", "say:after Bob 40");
    }

    @Test
    void aHostWithNoArgsReadsNothingAsBefore() {
        var root = new LinkedHashMap<String, Object>();
        root.put("player", Map.of("uuid", "u1"));
        root.put("vars", new LinkedHashMap<String, Object>());
        interpreter.start(rule("""
                - call: report
                """, """
                report:
                  - say: "[${args.who}]"
                """), new MapLogicContext(root), RunOwner.of("u1", null));

        assertThat(f.ran).containsExactly("say:[]");
    }
}
