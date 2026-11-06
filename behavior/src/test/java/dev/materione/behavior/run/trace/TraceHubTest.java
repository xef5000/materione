package dev.materione.behavior.run.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.trace.TraceBatch;
import dev.materione.api.logic.trace.TraceEntry;
import dev.materione.api.logic.trace.TraceFilter;
import dev.materione.api.logic.trace.TraceKind;
import dev.materione.api.logic.trace.TraceLimits;
import dev.materione.api.logic.trace.TraceSubscription;
import dev.materione.behavior.flow.FlowFixture;
import dev.materione.behavior.flow.StepPath;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.behavior.run.FlowInterpreter;
import dev.materione.behavior.run.ManualScheduler;
import dev.materione.behavior.run.RunBudget;
import dev.materione.behavior.run.RunOwner;
import dev.materione.behavior.run.TraceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The live trace hub (logic.md §7.6, §32): the subscription filter, the rate limit, the per-run step cap, the
 * pending cap and the concurrent-subscription cap, the drop marker, and that none of it can reach the engine.
 */
class TraceHubTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final String SHOP = "sample:shop";

    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<Boolean> activity = new ArrayList<>();
    private final List<TraceBatch> heard = new ArrayList<>();

    private TraceHub hub(TraceLimits limits) {
        return new TraceHub(limits, clock::get, activity::add);
    }

    private static TraceEvent event(long run, String resource, UUID player, TraceEvent.Type type, String path) {
        return new TraceEvent(run, resource, "r", player.toString(), StepPath.of(path.split("\\.")), type, "");
    }

    private static TraceEvent step(long run, int index) {
        return event(run, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do." + index);
    }

    // --- the filter ----------------------------------------------------------------

    @Test
    void aFilterMustNameAPlayerOrAResource() {
        assertThatThrownBy(() -> new TraceFilter(java.util.Set.of(), java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSubscriptionHearsOnlyItsResourceAndItsPlayer() {
        var hub = hub(TraceLimits.DEFAULT);
        hub.subscribe(TraceFilter.resource(SHOP, ALICE), heard::add);

        hub.accept(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0"));
        hub.accept(event(2, SHOP, BOB, TraceEvent.Type.STEP_OK, "behavior.0.do.0"));
        hub.accept(event(3, "sample:other", ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0"));
        hub.flush();

        assertThat(heard).hasSize(1);
        assertThat(heard.get(0).entries()).extracting(TraceEntry::runId).containsExactly(1L);
        assertThat(heard.get(0).dropped()).isZero();
    }

    @Test
    void aResourceOnlyFilterHearsEveryPlayerAndAPlayerOnlyFilterEveryResource() {
        var hub = hub(TraceLimits.DEFAULT);
        var byResource = new ArrayList<TraceBatch>();
        var byPlayer = new ArrayList<TraceBatch>();
        hub.subscribe(TraceFilter.resource(SHOP), byResource::add);
        hub.subscribe(new TraceFilter(java.util.Set.of(BOB), java.util.Set.of()), byPlayer::add);

        hub.accept(event(1, SHOP, ALICE, TraceEvent.Type.STEP_OK, "behavior.0.do.0"));
        hub.accept(event(2, "sample:other", BOB, TraceEvent.Type.STEP_OK, "behavior.0.do.0"));
        hub.flush();

        assertThat(byResource.get(0).entries()).extracting(TraceEntry::runId).containsExactly(1L);
        assertThat(byPlayer.get(0).entries()).extracting(TraceEntry::runId).containsExactly(2L);
    }

    @Test
    void theEntryCarriesThePathKindAndDetailOfTheEvent() {
        var hub = hub(TraceLimits.DEFAULT);
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        hub.accept(new TraceEvent(7, SHOP, "r", ALICE.toString(), StepPath.of("behavior", "0", "do", "1"),
                TraceEvent.Type.STEP_FAILED, "no money"));
        hub.flush();

        TraceEntry entry = heard.get(0).entries().get(0);
        assertThat(entry.path()).isEqualTo("behavior.0.do.1");
        assertThat(entry.kind()).isEqualTo(TraceKind.FAILED);
        assertThat(entry.detail()).isEqualTo("no money");
        assertThat(entry.atMillis()).isEqualTo(1_000);
        assertThat(entry.kind().wireName()).isEqualTo("failed");
        assertThat(TraceKind.REQUIRE_FAILED.wireName()).isEqualTo("require-failed");
    }

    @Test
    void aLongDetailIsCutWithAnEllipsis() {
        var hub = hub(TraceLimits.DEFAULT);
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        hub.accept(new TraceEvent(1, SHOP, "r", "", StepPath.of("behavior", "0"), TraceEvent.Type.STEP_FAILED,
                "x".repeat(1_000)));
        hub.flush();

        assertThat(heard.get(0).entries().get(0).detail()).hasSize(TraceLimits.DEFAULT.maxDetail()).endsWith("…");
    }

    // --- the limits ----------------------------------------------------------------

    @Test
    void theRateLimitDropsTheExcessAndSaysSo() {
        var hub = hub(new TraceLimits(10, 1_000, 1_000, 1_000, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        for (int i = 0; i < 25; i++) {
            hub.accept(step(i, 0)); // a different run each time, so only the rate applies
        }
        hub.flush();

        assertThat(heard.get(0).entries()).hasSize(10);
        assertThat(heard.get(0).dropped()).isEqualTo(15);
    }

    @Test
    void theRateRefillsWithTime() {
        var hub = hub(new TraceLimits(10, 1_000, 1_000, 1_000, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);
        for (int i = 0; i < 10; i++) {
            hub.accept(step(i, 0));
        }
        hub.accept(step(99, 0)); // the 11th inside the same instant is dropped
        clock.addAndGet(500); // half a second buys five more
        for (int i = 0; i < 8; i++) {
            hub.accept(step(100 + i, 0));
        }
        hub.flush();

        assertThat(heard.get(0).entries()).hasSize(15);
        assertThat(heard.get(0).dropped()).isEqualTo(4);
    }

    @Test
    void aRunPastItsStepCapKeepsItsStartAndEndEntries() {
        var hub = hub(new TraceLimits(10_000, 5, 1_000, 1_000, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        hub.accept(event(1, SHOP, ALICE, TraceEvent.Type.RUN_STARTED, "behavior.0"));
        for (int i = 0; i < 12; i++) {
            hub.accept(step(1, i));
        }
        hub.accept(event(1, SHOP, ALICE, TraceEvent.Type.RUN_ENDED, "behavior.0"));
        hub.flush();

        List<TraceKind> kinds = heard.get(0).entries().stream().map(TraceEntry::kind).toList();
        assertThat(kinds).hasSize(7).startsWith(TraceKind.RUN_STARTED).endsWith(TraceKind.RUN_ENDED);
        assertThat(heard.get(0).dropped()).isEqualTo(7);
    }

    @Test
    void theStepCapIsPerRunNotPerSubscription() {
        var hub = hub(new TraceLimits(10_000, 3, 1_000, 1_000, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        for (long run = 1; run <= 4; run++) {
            for (int i = 0; i < 3; i++) {
                hub.accept(step(run, i));
            }
        }
        hub.flush();

        assertThat(heard.get(0).entries()).hasSize(12);
        assertThat(heard.get(0).dropped()).isZero();
    }

    @Test
    void theQueueIsBoundedBetweenDeliveries() {
        var hub = hub(new TraceLimits(10_000, 10_000, 20, 1_000, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        for (int i = 0; i < 100; i++) {
            hub.accept(step(i, 0));
        }
        hub.flush();

        assertThat(heard.get(0).entries()).hasSize(20);
        assertThat(heard.get(0).dropped()).isEqualTo(80);
    }

    @Test
    void aDeliveryCarriesAtMostABatchAndTheRestWaitsForTheNext() {
        var hub = hub(new TraceLimits(10_000, 10_000, 1_000, 4, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);
        for (int i = 0; i < 10; i++) {
            hub.accept(step(i, 0));
        }

        hub.flush();
        assertThat(hub.hasPending()).isTrue();
        hub.flush();
        hub.flush();

        assertThat(heard).extracting(batch -> batch.entries().size()).containsExactly(4, 4, 2);
        assertThat(hub.hasPending()).isFalse();
    }

    @Test
    void aDropIsReportedEvenWhenNoEntrySurvived() {
        var hub = hub(new TraceLimits(10_000, 1, 1_000, 1_000, 8, 160));
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);
        hub.accept(step(1, 0));
        hub.flush();
        hub.accept(step(1, 1)); // past the run's cap
        hub.flush();

        assertThat(heard).hasSize(2);
        assertThat(heard.get(1).entries()).isEmpty();
        assertThat(heard.get(1).dropped()).isEqualTo(1);
        assertThat(heard.get(1).isEmpty()).isFalse();
    }

    @Test
    void theSubscriptionCapRefusesWithItsCodeAndLetsAClosedSlotBeReused() {
        var hub = hub(new TraceLimits(10, 10, 10, 10, 2, 160));
        var first = hub.subscribe(TraceFilter.resource("a:one"), heard::add);
        var second = hub.subscribe(TraceFilter.resource("a:two"), heard::add);
        var third = hub.subscribe(TraceFilter.resource("a:three"), heard::add);

        assertThat(first.ok() && second.ok()).isTrue();
        assertThat(third.ok()).isFalse();
        assertThat(third.diagnostics().get(0).code()).isEqualTo(DiagnosticCodes.TRACE_REFUSED);
        assertThat(hub.subscriptions()).isEqualTo(2);

        first.value().close();
        assertThat(hub.subscribe(TraceFilter.resource("a:three"), heard::add).ok()).isTrue();
    }

    // --- subscriptions and cost ------------------------------------------------------

    @Test
    void theHostIsToldWhenTheFirstSubscriptionOpensAndTheLastCloses() {
        var hub = hub(TraceLimits.DEFAULT);
        TraceSubscription a = hub.subscribe(TraceFilter.resource("a:one"), heard::add).value();
        TraceSubscription b = hub.subscribe(TraceFilter.resource("a:two"), heard::add).value();
        assertThat(activity).containsExactly(true);

        a.close();
        a.close(); // idempotent
        assertThat(activity).containsExactly(true);
        b.close();
        assertThat(activity).containsExactly(true, false);
        assertThat(a.active()).isFalse();
    }

    @Test
    void aClosedSubscriptionHearsNothingMore() {
        var hub = hub(TraceLimits.DEFAULT);
        TraceSubscription sub = hub.subscribe(TraceFilter.resource(SHOP), heard::add).value();
        sub.close();

        hub.accept(step(1, 0));
        hub.flush();

        assertThat(heard).isEmpty();
    }

    @Test
    void aListenerThatThrowsLosesItsBatchAndNothingElse() {
        var hub = hub(TraceLimits.DEFAULT);
        hub.subscribe(TraceFilter.resource(SHOP), batch -> {
            throw new IllegalStateException("broken listener");
        });
        hub.subscribe(TraceFilter.resource(SHOP), heard::add);

        hub.accept(step(1, 0));
        hub.flush();

        assertThat(heard).hasSize(1);
    }

    @Test
    void nothingIsBuiltWhenNobodySubscribes() {
        var hub = hub(TraceLimits.DEFAULT);

        hub.accept(step(1, 0)); // no subscription: dropped on the floor without a queue

        assertThat(hub.hasPending()).isFalse();
        assertThat(hub.subscriptions()).isZero();
    }

    @Test
    void closingTheHubClosesEverySubscription() {
        var hub = hub(TraceLimits.DEFAULT);
        TraceSubscription sub = hub.subscribe(TraceFilter.resource(SHOP), heard::add).value();

        hub.close();

        assertThat(sub.active()).isFalse();
        assertThat(hub.subscribe(TraceFilter.resource(SHOP), heard::add).ok()).isFalse();
    }

    // --- against the real interpreter --------------------------------------------------

    @Test
    void aStopStepLightsItsOwnNodeBeforeTheRunEnds() {
        var f = new FlowFixture();
        var interpreter = new FlowInterpreter(new ManualScheduler(), RunBudget.DEFAULT, diagnostic -> { });
        var hub = hub(TraceLimits.DEFAULT);
        interpreter.traceTo(hub);
        hub.subscribe(TraceFilter.resource(FlowFixture.OWNER.toString()), heard::add);
        var rule = f.compile("""
                - on: click
                  do:
                    - a: 1
                    - stop
                    - b: never
                """).rules().get(0);

        interpreter.start(rule, new MapLogicContext(Map.of("player", Map.of("uuid", ALICE.toString()),
                "vars", new java.util.LinkedHashMap<String, Object>())), RunOwner.of(ALICE.toString(), "anchor"));
        hub.flush();

        List<TraceEntry> entries = heard.get(0).entries();
        assertThat(entries).extracting(TraceEntry::path).containsExactly(
                "behavior.0", "behavior.0.do.0", "behavior.0.do.1", "behavior.0");
        assertThat(entries.get(2).kind()).isEqualTo(TraceKind.RAN);
        assertThat(entries.get(3).detail()).startsWith("STOPPED");
    }

    @Test
    void aRealRunProducesTheEntriesTheStudioDrawsFrom() {
        var f = new FlowFixture();
        var scheduler = new ManualScheduler();
        var interpreter = new FlowInterpreter(scheduler, RunBudget.DEFAULT, diagnostic -> { });
        var hub = hub(TraceLimits.DEFAULT);
        interpreter.traceTo(hub);
        hub.subscribe(TraceFilter.resource(FlowFixture.OWNER.toString(), ALICE), heard::add);
        var parsed = f.compile("""
                - on: click
                  do:
                    - a: 1
                    - if: "player.level > 5"
                      then: [ { b: 2 } ]
                      else: [ { c: 3 } ]
                    - fails: nope
                """);
        var rule = parsed.rules().get(0);

        interpreter.start(rule, new MapLogicContext(Map.of("player", Map.of("level", 12, "uuid", ALICE.toString()),
                "vars", new java.util.LinkedHashMap<String, Object>())), RunOwner.of(ALICE.toString(), "anchor"));
        hub.flush();

        List<TraceEntry> entries = heard.get(0).entries();
        assertThat(entries).extracting(TraceEntry::resource).containsOnly(FlowFixture.OWNER.toString());
        assertThat(entries).extracting(TraceEntry::player).containsOnly(ALICE.toString());
        assertThat(entries).extracting(TraceEntry::path).containsSubsequence(
                "behavior.0", "behavior.0.do.0", "behavior.0.do.1", "behavior.0.do.1.then.0", "behavior.0");
        assertThat(entries.get(0).kind()).isEqualTo(TraceKind.RUN_STARTED);
        assertThat(entries.get(entries.size() - 1).kind()).isEqualTo(TraceKind.RUN_ENDED);
        assertThat(entries).filteredOn(entry -> entry.path().equals("behavior.0.do.1"))
                .extracting(TraceEntry::detail).containsExactly("then");
    }
}
