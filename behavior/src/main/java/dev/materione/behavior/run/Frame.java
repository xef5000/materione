package dev.materione.behavior.run;

import dev.materione.behavior.flow.RequireStep;
import dev.materione.behavior.flow.Step;
import java.util.List;
import java.util.Map;

/**
 * One entry of a run's explicit stack (logic.md §5.2): a list of steps and how far through it the
 * run is. The interpreter pushes a frame to enter a branch, a {@code require} body or a called flow
 * and pops it when the list is exhausted, so a run is plain data that can be parked and resumed on
 * another thread, never a Java call stack.
 */
final class Frame {

    /** What the frame is for. */
    enum Kind {
        /** A branch or the rule's own steps. */
        BLOCK,
        /** The body of a {@code require}: a failed action anywhere beneath it triggers the require. */
        REQUIRE,
        /** The {@code else} steps of a failed {@code require}: when they end, the run ends. */
        FAIL_ELSE,
        /** A called flow's steps; it owns the {@code args}. */
        CALL
    }

    final Kind kind;
    final List<Step> steps;
    /** The {@code args.*} visible to this frame: its own for a call, else inherited from its parent. */
    final Map<String, Object> args;
    /** The require this frame is the body of (REQUIRE) or the else of (FAIL_ELSE), else null. */
    final RequireStep require;
    int index;

    private Frame(Kind kind, List<Step> steps, Map<String, Object> args, RequireStep require) {
        this.kind = kind;
        this.steps = steps;
        this.args = args;
        this.require = require;
    }

    static Frame block(List<Step> steps, Map<String, Object> args) {
        return new Frame(Kind.BLOCK, steps, args, null);
    }

    static Frame require(RequireStep require, Map<String, Object> args) {
        return new Frame(Kind.REQUIRE, require.steps(), args, require);
    }

    static Frame failElse(RequireStep require, Map<String, Object> args) {
        return new Frame(Kind.FAIL_ELSE, require.otherwise(), args, require);
    }

    static Frame call(List<Step> steps, Map<String, Object> args) {
        return new Frame(Kind.CALL, steps, args, null);
    }

    boolean exhausted() {
        return index >= steps.size();
    }
}
