package dev.materione.behavior.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which runs are alive beyond the call that started them, and how many each player has parked
 * (logic.md §5.3). Runs started by a rule gate are tracked from creation so cancellation cannot
 * miss the transition from a pending gate to a run. Other runs enter when they first park, or
 * at start when they carry a cancellation key.
 *
 * <p>Methods are synchronized: they are touched on park, resume, finish and cancel, never per step.
 */
final class RunRegistry {

    private final Set<FlowRun> tracked = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Integer> parkedByPlayer = new HashMap<>();

    /** Makes {@code run} reachable by cancellation. */
    synchronized void track(FlowRun run) {
        tracked.add(run);
        run.tracked = true;
    }

    /** Parked runs {@code playerKey} has now. */
    synchronized int parked(Object playerKey) {
        return parkedByPlayer.getOrDefault(playerKey, 0);
    }

    /**
     * Counts {@code run} as parked for its player unless the player is at {@code limit}.
     *
     * @return whether the park was admitted
     */
    synchronized boolean admitParked(FlowRun run, int limit) {
        Object key = run.owner().playerKey();
        if (!admitGate(key, limit)) {
            return false;
        }
        tracked.add(run);
        run.tracked = true;
        return true;
    }

    synchronized boolean admitGate(Object key, int limit) {
        int now = parkedByPlayer.getOrDefault(key, 0);
        if (now >= limit) {
            return false;
        }
        parkedByPlayer.put(key, now + 1);
        return true;
    }

    /** Ends the park accounting for {@code run} (it resumed, or it is finished). */
    synchronized void releaseParked(FlowRun run) {
        releaseGate(run.owner().playerKey());
    }

    synchronized void releaseGate(Object key) {
        int now = parkedByPlayer.getOrDefault(key, 0);
        if (now <= 1) {
            parkedByPlayer.remove(key);
        } else {
            parkedByPlayer.put(key, now - 1);
        }
    }

    /** Forgets {@code run}. */
    synchronized void untrack(FlowRun run) {
        tracked.remove(run);
    }

    /** The live runs whose cancellation key equals {@code cancelKey}. */
    synchronized List<FlowRun> byCancelKey(Object cancelKey) {
        var out = new ArrayList<FlowRun>();
        for (FlowRun run : tracked) {
            if (Objects.equals(run.owner().cancelKey(), cancelKey) && cancelKey != null) {
                out.add(run);
            }
        }
        return out;
    }

    /** The live runs of one player. */
    synchronized List<FlowRun> byPlayer(Object playerKey) {
        var out = new ArrayList<FlowRun>();
        for (FlowRun run : tracked) {
            if (Objects.equals(run.owner().playerKey(), playerKey)) {
                out.add(run);
            }
        }
        return out;
    }

    /** Tracked runs, for the doctor/debug surface. */
    synchronized int trackedRuns() {
        return tracked.size();
    }
}
