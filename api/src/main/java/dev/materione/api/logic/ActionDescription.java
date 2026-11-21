package dev.materione.api.logic;

import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * What the editor, the docs and the node palette know about an {@link ActionType}: its
 * {@link SelfDescription} (display name, summary, parameter schema, activation sentence), the
 * palette {@link LogicCategory}, the scalar shorthand, and which targets it can act on. One description per
 * type, ever (D5): the form the studio draws and the parameters {@link ActionType#parse} reads are the same schema.
 *
 * <p><b>Targetable.</b> An action that can act on something other than the run's own player declares the
 * {@link TargetKind kinds} it accepts. The declaration adds the shared {@code target} parameter to the schema, so
 * the editor's form and the code-mode SDK show it with no code of their own, and tells the engine to parse and
 * resolve a step's {@code target:} (logic.md §39); the action itself only reads {@link Subjects}.
 *
 * @param self display metadata and the parameter schema; a targetable action's schema gains {@code target}
 * @param category the palette group
 * @param shorthand the id of the parameter a bare scalar stands for ({@code message: "hi"} is
 *        {@code text}), or {@code null} when the action only takes a mapping; it must name a
 *        parameter declared in {@code self}
 * @param targetable the kinds of target the action accepts, in declaration order; empty when it always acts on the
 *        run's own player
 */
public record ActionDescription(
        SelfDescription self, LogicCategory category, String shorthand, List<TargetKind> targetable) {

    /** The id of the parameter every targetable action carries. */
    public static final String TARGET_PARAMETER = TargetSpec.KEY;

    public ActionDescription {
        Objects.requireNonNull(self, "self");
        Objects.requireNonNull(category, "category");
        shorthand = shorthand == null || shorthand.isBlank() ? null : shorthand;
        var kinds = EnumSet.noneOf(TargetKind.class);
        if (targetable != null) {
            kinds.addAll(targetable);
        }
        targetable = List.copyOf(kinds);
        if (!targetable.isEmpty()) {
            self = withTargetParameter(self);
        }
        if (shorthand != null) {
            String declared = shorthand;
            if (self.parameters().stream().map(ParameterSchema::id).noneMatch(declared::equals)) {
                throw new IllegalArgumentException(
                        "shorthand '" + shorthand + "' is not a declared parameter of '" + self.displayName() + "'");
            }
        }
    }

    /** A description whose action acts on the run's own player only. */
    public ActionDescription(SelfDescription self, LogicCategory category, String shorthand) {
        this(self, category, shorthand, List.of());
    }

    /** A description whose action takes a parameter mapping only. */
    public static ActionDescription of(SelfDescription self, LogicCategory category) {
        return new ActionDescription(self, category, null);
    }

    /**
     * This description, declaring that the action accepts a {@code target:} of these kinds (logic.md §39).
     * {@link TargetKind#ENTITY} implies players, so {@code withTargets(PLAYER, ENTITY)} and {@code withTargets(ENTITY)}
     * accept the same subjects; the first spelling says it in the vocabulary.
     */
    public ActionDescription withTargets(TargetKind... kinds) {
        return new ActionDescription(self, category, shorthand, List.of(kinds));
    }

    /** Whether the action can act on something other than the run's own player. */
    public boolean isTargetable() {
        return !targetable.isEmpty();
    }

    /** Whether a subject of {@code kind} is acceptable: a player is accepted by an action that takes entities. */
    public boolean admits(TargetKind kind) {
        return targetable.contains(kind) || kind == TargetKind.PLAYER && targetable.contains(TargetKind.ENTITY);
    }

    private static SelfDescription withTargetParameter(SelfDescription self) {
        if (self.parameters().stream().anyMatch(parameter -> parameter.id().equals(TARGET_PARAMETER))) {
            return self;
        }
        var parameters = new ArrayList<>(self.parameters());
        parameters.add(targetParameter());
        return new SelfDescription(self.displayName(), self.description(), parameters, self.sinceVersion(),
                self.activation(), self.supersededBy());
    }

    /** The shared {@code target} parameter every targetable action carries (logic.md §39). */
    public static ParameterSchema targetParameter() {
        return new ParameterSchema(TARGET_PARAMETER, ParameterType.RAW, false, "self",
                "Who or what the action acts on: self (the player the rule runs for), event.target (the entity the "
                        + "trigger carries), {arg: name}, {player: name}, or {nearby: {radius, type, limit}}",
                null, null).ofKind(ParameterSchema.KIND_TARGET);
    }
}
