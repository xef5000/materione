package dev.materione.plugin.logic.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.CodedRefusal;
import dev.materione.api.logic.Facets;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.ReadExecutor;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.behavior.logic.EngineExpressions;
import dev.materione.behavior.logic.MapLogicContext;
import dev.materione.plugin.gui.GuiBridge;
import dev.materione.plugin.logic.action.gui.GuiSlotItemAction;
import dev.materione.plugin.logic.condition.HasPermissionCondition;
import dev.materione.plugin.logic.condition.SlotHasCondition;
import dev.materione.plugin.logic.support.SlotPeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * The quantities that take an expression (logic.md section 42, #470, #470): a potion duration and amplifier, the title timings,
 * the sound volume and pitch, a prompt timeout (in {@code PromptActionTest}), a GUI slot, and a permission count. For each,
 * a literal keeps its load-time check and its message, a template is read when the step runs, and a template that
 * renders out of range refuses the step under {@code MTR-0120} before anything is done. Each runs against
 * {@link PlayerFake}, so no server is needed; a potion that would be applied is not, because the effect registry is the
 * live server's.
 */
class QuantityExpressionTest {

    private static ParamReader reader(Object... keyValues) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return ParamReader.of(map, EngineExpressions.INSTANCE);
    }

    /** The run's player with {@code args.*} set as an authored argument or a variable would be. */
    private static LogicContext withArgs(PlayerFake fake, Map<String, Object> args) {
        return new MapLogicContext(Map.of("args", args)).with(Facets.PLAYER, fake.player);
    }

    private static ParameterSchema schema(List<ParameterSchema> parameters, String id) {
        return parameters.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    private static String reasonOf(ActionOutcome outcome) {
        assertThat(outcome).isInstanceOf(ActionOutcome.Failed.class);
        return ((ActionOutcome.Failed) outcome).reason();
    }

    // --- potion ---------------------------------------------------------------------

    @Test
    void potionDurationAndAmplifierAreExpressionsAndTheLiteralsKeepTheirLoadChecks() {
        var potion = new PotionAction();
        assertThat(schema(potion.describe().self().parameters(), "duration").semantics().expression()).isTrue();
        assertThat(schema(potion.describe().self().parameters(), "amplifier").semantics().expression()).isTrue();

        var parsed = potion.parse(reader("type", "speed", "duration", 40, "amplifier", 2));
        assertThat(parsed.duration().literal()).isEqualTo(40);
        assertThat(parsed.amplifier().literal()).isEqualTo(2);
        assertThat(parsed.duration().isTemplate()).isFalse();

        assertThatThrownBy(() -> potion.parse(reader("type", "speed", "duration", 0)))
                .hasMessage("duration must be an integer in 1..2147483647");
        assertThatThrownBy(() -> potion.parse(reader("type", "speed", "amplifier", "2")))
                .hasMessage("amplifier must be an integer in 0..255");
    }

    @Test
    void aPotionTemplateIsReadWhenTheStepRunsAndARefusedOneChangesNothing() {
        var potion = new PotionAction();
        var fake = new PlayerFake();
        var parsed = potion.parse(reader("type", "speed", "duration", "${args.d}", "amplifier", "${args.a}"));
        assertThat(parsed.duration().isTemplate()).isTrue();

        for (Object bad : List.of("lots", "0", "1.5")) {
            var outcome = potion.execute(parsed, withArgs(fake, Map.of("d", bad, "a", 0)));
            assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
                assertThat(failed.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
                assertThat(failed.reason()).startsWith("duration must be a whole number in 1..2147483647, not \"" + bad);
            });
        }
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void aRefusedPotionTemplateIsRefusedWithItsCodeOnTheResumablePathToo() {
        var potion = new PotionAction();
        var fake = new PlayerFake();
        var parsed = potion.parse(reader("type", "speed", "amplifier", "${args.a}"));

        ActionOutcome outcome = potion.executeResumable(parsed, withArgs(fake, Map.of("a", 300)), ReadExecutor.INLINE)
                .toCompletableFuture().join();
        assertThat(outcome).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
            assertThat(failed.reason()).startsWith("amplifier must be a whole number in 0..255, not \"300\"");
        });
    }

    // --- title ----------------------------------------------------------------------

    @Test
    void titleTimingsAreExpressionsAndALiteralIsCheckedAtLoad() {
        var title = new TitleAction();
        for (String id : List.of("fade_in", "stay", "fade_out")) {
            assertThat(schema(title.describe().self().parameters(), id).semantics().expression()).isTrue();
        }
        assertThatThrownBy(() -> title.parse(reader("title", "Hi", "fade_in", "5.5")))
                .hasMessageStartingWith("fade_in must be an integer");
        assertThatThrownBy(() -> title.parse(reader("title", "Hi", "stay", "-1")))
                .hasMessageStartingWith("stay must be an integer");
    }

    @Test
    void aTitleTemplateTimingIsReadWhenShownAndARefusedOneShowsNoTitle() {
        var title = new TitleAction();
        var fake = new PlayerFake();
        var parsed = title.parse(reader("title", "Hi", "fade_in", "${args.in}", "stay", 40, "fade_out", "${args.out}"));

        assertThat(title.execute(parsed, withArgs(fake, Map.of("in", 5, "out", 10)))).isEqualTo(ActionOutcome.OK);
        assertThat(fake.calls).containsExactly("title:Hi||5,40,10");

        ActionOutcome refused = title.execute(parsed, withArgs(fake, Map.of("in", 5, "out", -3)));
        assertThat(refused).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
            assertThat(failed.reason()).isEqualTo("fade_out must be a whole number in 0..72000, not \"-3\"");
        });
        assertThat(fake.calls).containsExactly("title:Hi||5,40,10");
    }

    // --- sound ----------------------------------------------------------------------

    @Test
    void soundVolumeAndPitchAreExpressionsAndTemplatesAreReadWhenThePlaySoundRuns() {
        var sound = new SoundAction();
        assertThat(schema(sound.describe().self().parameters(), "volume").semantics().expression()).isTrue();
        assertThat(schema(sound.describe().self().parameters(), "pitch").semantics().expression()).isTrue();
        assertThatThrownBy(() -> sound.parse(reader("sound", "minecraft:x", "volume", "loud")))
                .hasMessageStartingWith("volume must be a finite number in 0.0..1000.0");

        var fake = new PlayerFake();
        var parsed = sound.parse(reader("sound", "minecraft:x", "volume", "${args.v}", "pitch", 2));
        assertThat(sound.execute(parsed, withArgs(fake, Map.of("v", 0.5)))).isEqualTo(ActionOutcome.OK);
        assertThat(fake.calls).containsExactly("sound:minecraft:x:0.5:2.0");

        ActionOutcome refused = sound.execute(parsed, withArgs(fake, Map.of("v", -1)));
        assertThat(refused).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
            assertThat(failed.reason()).isEqualTo("volume must be a number in 0.0..1000.0, not \"-1\"");
        });
        assertThat(fake.calls).hasSize(1);
    }

    // --- slot_has -------------------------------------------------------------------

    @Test
    void slotHasTakesATemplateSlotAndRefusesAnOutOfRangeOneWithItsCode() {
        var condition = new SlotHasCondition(SlotPeek.none());
        assertThat(schema(condition.describe().self().parameters(), "slot").semantics().expression()).isTrue();
        assertThatThrownBy(() -> condition.parse(reader("slot", 60)))
                .hasMessage("slot must be an integer in 0..53");

        var fake = new PlayerFake();
        var parsed = condition.parse(reader("slot", "${args.s}"));
        assertThat(condition.test(parsed, withArgs(fake, Map.of("s", 4)))).isFalse(); // no GUI open: false, not refused

        assertThatThrownBy(() -> condition.test(parsed, withArgs(fake, Map.of("s", 99))))
                .isInstanceOfSatisfying(CodedRefusal.class, refusal -> {
                    assertThat(refusal.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
                    assertThat(refusal.getMessage()).isEqualTo("slot must be a whole number in 0..53, not \"99\"");
                });
        assertThatThrownBy(() -> condition.testResumable(parsed, withArgs(fake, Map.of("s", 99)), ReadExecutor.INLINE)
                .toCompletableFuture().join())
                .isInstanceOfSatisfying(CompletionException.class, failure -> assertThat(failure.getCause())
                        .isInstanceOfSatisfying(CodedRefusal.class,
                                refusal -> assertThat(refusal.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED)));
    }

    // --- gui.slot_item --------------------------------------------------------------

    @Test
    void guiSlotItemTakesATemplateSlotAndRefusesAnOutOfRangeOneBeforeAnyGuiIsAsked() {
        var action = new GuiSlotItemAction(new GuiBridge());
        assertThat(schema(action.describe().self().parameters(), "slot").semantics().expression()).isTrue();
        assertThatThrownBy(() -> action.parse(reader("slot", 54, "op", "clear")))
                .hasMessage("slot must be an integer in 0..53");

        var fake = new PlayerFake();
        var parsed = action.parse(reader("slot", "${args.s}", "op", "clear"));
        ActionOutcome refused = action.execute(parsed, withArgs(fake, Map.of("s", 60)));
        assertThat(refused).isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
            assertThat(failed.reason()).isEqualTo("slot must be a whole number in 0..53, not \"60\"");
        });
        // a slot in range passes the check; with no GUI open the action says so, without a refusal code
        assertThat(action.execute(parsed, withArgs(fake, Map.of("s", 13))))
                .isInstanceOfSatisfying(ActionOutcome.Failed.class, failed -> assertThat(failed.code()).isNull());
    }

    // --- has_permission -------------------------------------------------------------

    @Test
    void hasPermissionMinTakesATemplateAndRefusesACountOutsideTheNodeList() {
        var condition = new HasPermissionCondition();
        assertThat(schema(condition.describe().self().parameters(), "min").semantics().expression()).isTrue();
        assertThatThrownBy(() -> condition.parse(reader("permissions", List.of("a.b", "c.d"), "min", 5)))
                .hasMessage("min must be an integer in 0..2");

        var fake = new PlayerFake();
        fake.permissions.add("a.b");
        var parsed = condition.parse(reader("permissions", List.of("a.b", "c.d"), "min", "${args.m}"));
        assertThat(condition.test(parsed, withArgs(fake, Map.of("m", 1)))).isTrue();
        assertThat(condition.test(parsed, withArgs(fake, Map.of("m", 2)))).isFalse();

        assertThatThrownBy(() -> condition.test(parsed, withArgs(fake, Map.of("m", 3))))
                .isInstanceOfSatisfying(CodedRefusal.class, refusal -> {
                    assertThat(refusal.code()).isEqualTo(DiagnosticCodes.QUANTITY_REFUSED);
                    assertThat(refusal.getMessage()).isEqualTo("min must be a whole number in 0..2, not \"3\"");
                });
    }
}
