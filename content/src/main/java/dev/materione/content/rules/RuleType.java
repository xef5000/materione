package dev.materione.content.rules;

import dev.materione.api.NamespacedId;
import dev.materione.api.content.Allocations;
import dev.materione.api.content.ContentType;
import dev.materione.api.content.PackEmitter;
import dev.materione.api.content.ResourcePresentation;
import dev.materione.api.registry.ParameterSchema;
import dev.materione.api.registry.ParameterType;
import dev.materione.api.registry.SelfDescription;
import dev.materione.content.Templates;
import dev.materione.content.logic.LogicSchemas;
import dev.materione.core.document.LosslessYaml;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code rule} content type (logic.md §37): a document of rules that is not hosted by an item,
 * block, furniture or GUI. It has the exact shape of a host's {@code behavior:} and {@code flows:}, so the
 * flow compiler, the interpreter, the trace and every {@code LogicEdit} treat it like any host; what it
 * adds is that the world fires its triggers ({@code entity.hit}, {@code player.join}, ...) with no
 * resource in the way.
 *
 * <pre>{@code
 * greet:
 *   description: Welcome players
 *   behavior:
 *     - on: player.join
 *       do: [ { message: "Welcome, ${player.name}" } ]
 * }</pre>
 *
 * <p>Nothing is emitted to the pack: a rule is runtime logic. Parsing is pure and never throws; a
 * document whose {@code behavior:} is not a list, or whose {@code flows:} is not a mapping, parses to
 * nothing and the pipeline quarantines it. A bad rule inside a good document is the flow compiler's
 * business and skips that rule alone ({@code MTR-0021}).
 */
public final class RuleType implements ContentType {

    /** The name the registry, the vocabulary's {@code firedBy} and the editor know this host by. */
    public static final String NAME = "rule";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String directory() {
        // plural like items/ and blocks/: the pipeline derives the type by stripping the trailing "s"
        return "rules";
    }

    @Override
    public SelfDescription describe() {
        return new SelfDescription(
                "Rule",
                "Rules and named flows that answer world events (an entity hit or killed, a player joining) with "
                        + "no item, block, furniture or GUI to hold them",
                List.of(
                        new ParameterSchema("description", ParameterType.STRING, false, null,
                                "A note for the people who edit this document", null, null),
                        LogicSchemas.behavior("ECA rules this document holds; each answers one trigger"),
                        LogicSchemas.flows()),
                "1.0.0");
    }

    @Override
    public Optional<Object> parse(
            NamespacedId id, Map<String, Object> definition, Map<String, Map<String, Object>> templates) {
        var merged = Templates.merge(definition, id.namespace(), templates);
        if (merged == null) {
            return Optional.empty(); // unknown or diamond template: quarantine upstream
        }
        Object behavior = merged.get("behavior");
        if (behavior != null && !(behavior instanceof List<?>)) {
            return Optional.empty();
        }
        Object flows = merged.get("flows");
        if (flows != null && !(flows instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<Object> rules = behavior == null ? List.of() : (List<Object>) LosslessYaml.stringKeyedDeep(behavior);
        @SuppressWarnings("unchecked")
        Map<String, Object> named = flows == null ? null : (Map<String, Object>) LosslessYaml.stringKeyedDeep(flows);
        return Optional.of(new RuleDefinition(id, Templates.text(merged, "description"), rules, named, merged));
    }

    @Override
    public Optional<ResourcePresentation> presentation(Object parsedDefinition) {
        if (!(parsedDefinition instanceof RuleDefinition rule)) {
            return Optional.empty();
        }
        return Optional.of(new ResourcePresentation(rule.description(), null, null, null));
    }

    @Override
    public void compile(Object definition, NamespacedId allocatedModelId, PackEmitter emitter, Allocations allocations) {
        // no pack output: a rule is runtime logic, compiled into the shadow's rule view
    }
}
