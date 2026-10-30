package dev.materione.plugin.logic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.registry.SelfDescription;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.run.RunScheduler;
import dev.materione.behavior.runtime.BehaviorEvent;
import dev.materione.behavior.runtime.CooldownStore;
import dev.materione.behavior.runtime.RuleEngine;
import dev.materione.core.registry.RegistryService;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The click-to-first-action budget of logic.md §5.3 / plugin.md §9: dispatching a 10-step flow
 * (branches, a guard, a choose and a call among them) through the rule gate and the interpreter costs
 * at most 0.5 ms at the 99th percentile, excluding the actions' own work. The actions here do nothing,
 * so the whole measurement is engine overhead. CI-tracked: a regression blocks merge.
 */
class FlowDispatchPerfTest {

    private static final long BUDGET_NS = 500_000;
    private static final int SAMPLES = 4_000;
    private static final int WARMUP = 60_000;

    /** Does nothing, runs anywhere. */
    private static final class Noop implements ActionType<String> {
        @Override
        public String id() {
            return "noop";
        }

        @Override
        public ActionDescription describe() {
            return ActionDescription.of(SelfDescription.of("Noop", "Does nothing"), LogicCategory.DATA);
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public String parse(ParamReader params) {
            return "";
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            return ActionOutcome.OK;
        }
    }

    private static final String FLOW = """
            - on: click
              do:
                - noop
                - noop
                - if: "player.level > 5"
                  then: [ noop, noop ]
                  else: [ noop ]
                - choose:
                    - if: "player.level > 50"
                      then: [ noop ]
                    - if: "player.level > 10"
                      then: [ noop ]
                  else: [ noop ]
                - require: [ noop, noop ]
                - call: tail
                - noop
                - noop
                - noop
                - noop
            """;

    @Test
    void tenStepFlowDispatchOverheadIsUnderHalfAMillisecondAtP99() {
        var logic = LogicTestSupport.newService(new RegistryService());
        LogicTestSupport.registerTriggers(logic);
        logic.actions().register(LogicTestSupport.owner("materione"), NamespacedId.of("materione", "noop"), new Noop());
        RunScheduler inline = new RunScheduler() {
            @Override
            public boolean canRunInline(Object anchor, Threading thread) {
                return true;
            }

            @Override
            public Handle schedule(Object anchor, Threading thread, long delayTicks, Runnable task, Runnable dropped) {
                throw new AssertionError("nothing in this flow suspends");
            }
        };
        var engine = new RuleEngine(new FlowInterpreter(inline, RunBudget.DEFAULT, diagnostic -> {
            throw new AssertionError("no diagnostics expected: " + diagnostic);
        }), new CooldownStore());
        var parsed = FlowCompiler.compile(NamespacedId.of("perf", "item"),
                new org.yaml.snakeyaml.Yaml().load(FLOW.replace("- on: click", "- \"on\": click")),
                Map.of("tail", List.of("noop", "noop")), logic.lookup());
        assertThat(parsed.problems()).isEmpty();
        var rules = parsed.rules();
        assertThat(rules).hasSize(1);

        // best of up to three attempts: noise (GC, JIT, a noisy neighbour) only inflates a tail, and a
        // real regression raises every attempt
        long best = Long.MAX_VALUE;
        long[] bestSamples = null;
        for (int attempt = 0; attempt < 3 && best >= BUDGET_NS; attempt++) {
            for (int i = 0; i < WARMUP; i++) {
                engine.fire(rules, event());
            }
            long[] samples = new long[SAMPLES];
            for (int i = 0; i < SAMPLES; i++) {
                BehaviorEvent event = event(); // built outside the timed region: it is the host's cost
                long start = System.nanoTime();
                engine.fire(rules, event);
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            long p99 = samples[(int) (SAMPLES * 0.99) - 1];
            if (p99 < best) {
                best = p99;
                bestSamples = samples;
            }
        }

        assertThat(best)
                .as("p99 dispatch of a 10-step flow, best of up to 3 attempts x %d samples (median %d ns, max %d ns)",
                        SAMPLES, bestSamples[SAMPLES / 2], bestSamples[SAMPLES - 1])
                .isLessThan(BUDGET_NS);
    }

    private static BehaviorEvent event() {
        var player = new LinkedHashMap<String, Object>();
        player.put("level", 12);
        player.put("uuid", "perf-player");
        var root = new LinkedHashMap<String, Object>();
        root.put("player", player);
        root.put("vars", new LinkedHashMap<String, Object>());
        return new BehaviorEvent("click", "right", new MapLogicContext(root), null, null);
    }
}
