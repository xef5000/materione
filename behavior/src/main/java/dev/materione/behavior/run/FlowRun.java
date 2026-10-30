package dev.materione.behavior.run;

import dev.materione.api.logic.ContextFacet;
import dev.materione.api.logic.LogicContext;
import dev.materione.behavior.expr.EvaluationContext;
import dev.materione.behavior.flow.Step;
import dev.materione.behavior.logic.EvaluationAware;
import dev.materione.behavior.rule.Rule;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One execution of a rule's flow (logic.md §5.2): an explicit frame stack plus the context it runs
 * against. It is a resumable state machine, not a thread: a {@code wait}, a {@code delay:} or a
 * thread hop parks it (the stack and context stay as they are), and the {@link RunScheduler} resumes
 * it later on the right thread. Only one thread touches a run at a time; the scheduler's hand-off
 * is the memory barrier.
 *
 * <p>The state is the interpreter's; callers hold a run to observe it ({@link #outcome},
 * {@link #isParked}) or to cancel it through {@link FlowInterpreter#cancel}.
 */
public final class FlowRun {

    private final long id;
    private final Rule rule;
    private final RunOwner owner;
    private final RunContext view;

    final Deque<Frame> stack = new ArrayDeque<>(4);
    final AtomicBoolean finished = new AtomicBoolean();
    volatile RunOutcome outcome;
    volatile boolean cancelled;
    volatile boolean parked;
    volatile RunScheduler.Handle pending = RunScheduler.Handle.NONE;
    /** Bumped on every park, so a stale resume (cancelled, or fired twice) is recognised and ignored. */
    long parkToken;
    /** Whether the park counts against the per-player limit (a wait or delay, not a thread hop). */
    boolean timedPark;
    /** Whether the registry holds this run (set by the registry). */
    volatile boolean tracked;
    int steps;
    int calls;
    /** The step being executed, so a resume continues it instead of counting it again. */
    Step armed;
    /** 0 fresh, 1 chance rolled, 2 delay served: where an action step is when the run resumes. */
    int stage;
    /** Set while resuming from a hop: the step runs where the scheduler put it, whatever the check says. */
    boolean hopped;

    FlowRun(long id, Rule rule, LogicContext context, RunOwner owner) {
        this.id = id;
        this.rule = rule;
        this.owner = owner;
        this.view = new RunContext(context, this);
    }

    public long id() {
        return id;
    }

    public Rule rule() {
        return rule;
    }

    public RunOwner owner() {
        return owner;
    }

    /** How the run ended, or null while it is live (running or parked). */
    public RunOutcome outcome() {
        return outcome;
    }

    public boolean isFinished() {
        return finished.get();
    }

    /** Whether the run is currently waiting for the scheduler. */
    public boolean isParked() {
        return parked && !finished.get();
    }

    /** Steps executed so far, counted against the budget. */
    public int stepsExecuted() {
        return steps;
    }

    /** The context steps see: the host's, plus the current frame's {@code args}. */
    LogicContext view() {
        return view;
    }

    Map<String, Object> currentArgs() {
        Frame top = stack.peek();
        return top == null ? null : top.args;
    }

    /**
     * The innermost require frame beneath (or at) the top of the stack, or null. A failed
     * require's else frame shields the requires below it: that run is already ending, and a
     * failing message in its else must not trigger an outer guard.
     */
    Frame nearestRequire() {
        for (Frame frame : stack) { // iterates from the top
            if (frame.kind == Frame.Kind.REQUIRE) {
                return frame;
            }
            if (frame.kind == Frame.Kind.FAIL_ELSE) {
                return null;
            }
        }
        return null;
    }

    /**
     * The host's context with {@code args} resolved from the current frame. Everything else (facets,
     * run-local variables, placeholders) is the host's own, so an action cannot tell the difference.
     */
    private static final class RunContext implements LogicContext, EvaluationAware {

        private final LogicContext base;
        private final FlowRun run;
        private final EvaluationContext evaluation;

        RunContext(LogicContext base, FlowRun run) {
            this.base = base;
            this.run = run;
            this.evaluation = EvaluationContext.of(this::resolve).withLogic(this);
        }

        @Override
        public <T> Optional<T> facet(ContextFacet<T> facet) {
            return base.facet(facet);
        }

        @Override
        public Object resolve(List<String> path) {
            if (!path.isEmpty() && "args".equals(path.get(0))) {
                Object current = run.currentArgs();
                for (int i = 1; i < path.size(); i++) {
                    if (!(current instanceof Map<?, ?> map)) {
                        return null;
                    }
                    current = map.get(path.get(i));
                }
                return path.size() == 1 ? run.currentArgs() : current;
            }
            return base.resolve(path);
        }

        @Override
        public Map<String, Object> locals() {
            return base.locals();
        }

        @Override
        public String applyPlaceholders(String text) {
            return base.applyPlaceholders(text);
        }

        @Override
        public EvaluationContext evaluation() {
            return evaluation;
        }
    }
}
