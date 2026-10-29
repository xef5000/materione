package dev.materione.behavior.logic;

import dev.materione.api.NamespacedId;
import dev.materione.api.logic.ActionDescription;
import dev.materione.api.logic.ActionOutcome;
import dev.materione.api.logic.ActionType;
import dev.materione.api.logic.Cancellation;
import dev.materione.api.logic.LogicCategory;
import dev.materione.api.logic.LogicContext;
import dev.materione.api.logic.ParamReader;
import dev.materione.api.logic.Threading;
import dev.materione.api.logic.TriggerType;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.api.registry.RegistryKeys;
import dev.materione.core.registry.RegistryService;
import java.util.List;

/**
 * Real registries, a real lookup, and a handful of fake actions: what the engine tests run rules
 * against. The built-in triggers are registered the way the plugin registers them.
 */
public final class LogicFixture {

    private final RegistryService registries = new RegistryService();

    public LogicFixture() {
        var triggers = registries.registry(RegistryKeys.TRIGGERS, TriggerType.class);
        for (TriggerType trigger : BuiltinTriggers.all()) {
            triggers.register(NamespacedId.of("materione", trigger.id()), trigger,
                    trigger.describe().self(), "test");
        }
    }

    public RegistryService registries() {
        return registries;
    }

    public RegistryLogicLookup lookup() {
        return new RegistryLogicLookup(registries);
    }

    /** Registers {@code action} under {@code namespace:<its id>}. */
    @SuppressWarnings("rawtypes")
    public LogicFixture action(String namespace, ActionType<?> action) {
        registries.registry(RegistryKeys.ACTIONS, ActionType.class).register(
                NamespacedId.of(namespace, action.id()), action, action.describe().self(), "test");
        return this;
    }

    /** Registers {@code action} under {@code materione:<its id>}. */
    public LogicFixture action(ActionType<?> action) {
        return action("materione", action);
    }

    /**
     * An action that records {@code id:value} into {@code ran}; its single parameter {@code value}
     * is the scalar shorthand. The id {@code boom} throws and {@code fails} returns a failure.
     */
    public static Recording recording(String id, Threading threading, List<String> ran) {
        return new Recording(id, threading, ran);
    }

    /** The recording fake. */
    public static final class Recording implements ActionType<String> {
        private final String id;
        private final Threading threading;
        private final List<String> ran;

        private Recording(String id, Threading threading, List<String> ran) {
            this.id = id;
            this.threading = threading;
            this.ran = ran;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription(id, "A test action",
                    List.of(ParameterSchema.of("value", ParameterType.STRING, false)), "1.0.0"),
                    LogicCategory.DATA, "value");
        }

        @Override
        public Threading threading() {
            return threading;
        }

        @Override
        public String parse(ParamReader params) {
            String value = params.raw("value").map(String::valueOf).orElse("");
            if (value.equals("reject")) {
                throw new IllegalArgumentException("value must not be 'reject'");
            }
            return value;
        }

        @Override
        public ActionOutcome execute(String params, LogicContext context) {
            if (id.equals("boom")) {
                throw new IllegalStateException("action exploded");
            }
            if (id.equals("fails")) {
                return ActionOutcome.failed("no luck");
            }
            ran.add(id + ":" + params);
            return ActionOutcome.OK;
        }
    }

    /** {@code cancel: true}, reading the cancellation facet the way the real action does. */
    public static final class CancelFake implements ActionType<Boolean> {
        @Override
        public String id() {
            return "cancel";
        }

        @Override
        public ActionDescription describe() {
            return new ActionDescription(new SelfDescription("Cancel", "Cancels the event",
                    List.of(ParameterSchema.of("value", ParameterType.BOOLEAN, false)), "1.0.0"),
                    LogicCategory.FLOW, "value");
        }

        @Override
        public Threading threading() {
            return Threading.ANY;
        }

        @Override
        public Boolean parse(ParamReader params) {
            return params.bool("value", true);
        }

        @Override
        public ActionOutcome execute(Boolean params, LogicContext context) {
            if (params) {
                context.require(Cancellation.FACET).request();
            }
            return ActionOutcome.OK;
        }
    }
}
