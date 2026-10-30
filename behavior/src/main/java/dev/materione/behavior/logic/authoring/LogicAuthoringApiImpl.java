package dev.materione.behavior.logic.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.NamespacedId;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.authoring.AuthoringApi;
import dev.materione.api.authoring.AuthoringApi.DocumentView;
import dev.materione.api.authoring.AuthoringApi.FieldEdit;
import dev.materione.api.authoring.AuthoringApi.SetFieldsRequest;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.diagnostics.Severity;
import dev.materione.api.logic.LogicAuthoringApi;
import dev.materione.api.logic.LogicEdit;
import dev.materione.behavior.flow.FlowCompiler;
import dev.materione.behavior.logic.LogicLookup;
import dev.materione.behavior.rule.ParsedRules;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@link LogicAuthoringApi} over the document store (logic.md §8, api.md §3.3). Reads and writes go
 * through {@link AuthoringApi}: an edit is one revision-checked {@code setFields} that rewrites the
 * resource's {@code behavior:} and {@code flows:} values, so D6 holds (one pipeline, no shortcut).
 * Validation runs the real {@link FlowCompiler} over the draft with the registries' lookup.
 *
 * <p>The edit applies to a copy of the resource first ({@link LogicEditor}); a refused edit writes
 * nothing, and an edit that changes nothing writes nothing. Only the resources that carry logic are
 * addressable: items, blocks, furniture and GUIs, one resource per file.
 */
public final class LogicAuthoringApiImpl implements LogicAuthoringApi {

    /** The folders whose documents hold a resource with {@code behavior:} (gui.md §2, items, blocks, furniture). */
    private static final List<String> RESOURCE_FOLDERS = List.of("items/", "blocks/", "furniture/", "guis/");

    private final AuthoringApi authoring;
    private final LogicLookup lookup;

    /**
     * @param authoring the authoring API the reads and the writes go through
     * @param lookup the registries actions, conditions and triggers are resolved against
     */
    public LogicAuthoringApiImpl(AuthoringApi authoring, LogicLookup lookup) {
        this.authoring = Objects.requireNonNull(authoring, "authoring");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /** A resource's authored mapping, with the file it lives in. */
    record Loaded(String pack, String path, String resourceId, int revision, Map<String, Object> resource) {}

    @Override
    public ApiResult<LogicView> get(String pack, String path) {
        ApiResult<Loaded> loaded = load(pack, path);
        if (!loaded.ok()) {
            return ApiResult.fail(loaded.diagnostics());
        }
        return ApiResult.ok(view(loaded.value()));
    }

    @Override
    public ApiResult<LogicView> edit(ActorContext actor, LogicEditRequest request) {
        ApiResult<Loaded> loaded = load(request.pack(), request.path());
        if (!loaded.ok()) {
            return ApiResult.fail(loaded.diagnostics());
        }
        Loaded before = loaded.value();
        if (before.revision() != request.expectedRevision()) {
            return new ApiResult<>(false, null, List.of(new Diagnostic(
                    DiagnosticCodes.EDITOR_DOCUMENT_CONFLICT, Severity.WARN,
                    before.path() + " is at revision " + before.revision() + ", not " + request.expectedRevision(),
                    null, null, "re-read the logic and re-apply your change")));
        }
        Map<String, Object> working = copyOf(before.resource());
        var editor = new LogicEditor(working, lookup);
        try {
            for (LogicEdit edit : request.edits()) {
                editor.apply(edit);
            }
        } catch (LogicRefusal refused) {
            return ApiResult.fail(refused.diagnostic(NamespacedId.of(before.pack(), before.resourceId())));
        }
        List<FieldEdit> fields = changedFields(before, working);
        if (fields.isEmpty()) {
            return ApiResult.ok(view(before), compileProblems(before.pack(), before.resourceId(), before.resource()));
        }
        ApiResult<DocumentView> saved;
        try {
            saved = authoring.setFields(actor, new SetFieldsRequest(
                    before.pack(), before.path(), before.revision(), fields));
        } catch (RuntimeException failure) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.PIPELINE_STAGE_FAILED,
                    failure.getMessage() != null ? failure.getMessage() : failure.toString()));
        }
        if (!saved.ok()) {
            return ApiResult.fail(saved.diagnostics());
        }
        ApiResult<Loaded> after = fromDocument(before.pack(), before.path(), saved.value());
        if (!after.ok()) {
            return ApiResult.fail(after.diagnostics());
        }
        Loaded now = after.value();
        return ApiResult.ok(view(now), compileProblems(now.pack(), now.resourceId(), now.resource()));
    }

    @Override
    public ApiResult<List<Diagnostic>> validate(LogicDraft draft) {
        String resourceId = draft.resourceId() == null ? "draft" : draft.resourceId();
        NamespacedId owner;
        try {
            owner = NamespacedId.of(draft.pack(), resourceId);
        } catch (IllegalArgumentException malformed) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID,
                    "'" + draft.pack() + ":" + resourceId + "' is not a valid pack and id"));
        }
        List<Object> behavior = withTrigger(draft.behavior(), draft.trigger());
        Map<String, Object> flows = draft.flows().isEmpty() ? null : draft.flows();
        ParsedRules parsed = FlowCompiler.compile(owner, behavior, flows, lookup);
        return ApiResult.ok(parsed.problems());
    }

    @Override
    public ApiResult<List<Map<String, Object>>> instantiate(InstantiateRequest request) {
        return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID,
                "unknown recipe '" + request.recipe() + "': the recipe registry arrives with #222"));
    }

    // ------------------------------------------------------------------
    // reads

    /** The file's one resource, or the reason it cannot be addressed by this surface. */
    private ApiResult<Loaded> load(String pack, String path) {
        if (path == null || RESOURCE_FOLDERS.stream().noneMatch(path::startsWith)) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID,
                    "'" + path + "' is not a document that holds logic (items/, blocks/, furniture/ or guis/)"));
        }
        ApiResult<DocumentView> document = authoring.getDocument(pack, path);
        if (!document.ok()) {
            return ApiResult.fail(document.diagnostics());
        }
        return fromDocument(pack, path, document.value());
    }

    private static ApiResult<Loaded> fromDocument(String pack, String path, DocumentView document) {
        if (document.parsed() == null) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_PARSE_FAILED,
                    "document " + path + " does not parse as YAML"));
        }
        String resourceId = null;
        Map<String, Object> resource = null;
        int count = 0;
        for (Map.Entry<String, Object> entry : document.parsed().entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> mapping) {
                count++;
                resourceId = entry.getKey();
                resource = StepShape.stringKeyed(mapping);
            }
        }
        if (count != 1) {
            return ApiResult.fail(Diagnostic.error(DiagnosticCodes.CONTENT_SCHEMA_INVALID, count == 0
                    ? "the document holds no resource" : "the document holds " + count
                    + " resources; the logic API works on one resource per file"));
        }
        return ApiResult.ok(new Loaded(pack, path, resourceId, document.revision(), resource));
    }

    private static LogicView view(Loaded loaded) {
        return LogicViews.view(loaded.pack(), loaded.path(), loaded.resourceId(), loaded.revision(), loaded.resource());
    }

    /** The compiler's problems for the resource as it stands: the warnings an accepted edit reports. */
    private List<Diagnostic> compileProblems(String pack, String resourceId, Map<String, Object> resource) {
        NamespacedId owner = NamespacedId.of(pack, resourceId);
        return FlowCompiler.compile(owner, resource.get("behavior"), resource.get("flows"), lookup).problems();
    }

    // ------------------------------------------------------------------
    // writes

    /** The field edits that take the file from {@code before} to {@code after}: whole values, or a removal. */
    private static List<FieldEdit> changedFields(Loaded before, Map<String, Object> after) {
        var fields = new ArrayList<FieldEdit>();
        for (String key : List.of("behavior", "flows")) {
            Object was = before.resource().get(key);
            Object now = after.get(key);
            if (Objects.equals(was, now)) {
                continue;
            }
            List<String> field = List.of(before.resourceId(), key);
            fields.add(now == null ? FieldEdit.removal(field) : new FieldEdit(field, now));
        }
        return fields;
    }

    private static Map<String, Object> copyOf(Map<String, Object> resource) {
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) LogicTree.copy(resource);
        return copy;
    }

    /** The draft's rules, with the trigger as the {@code on:} of each rule that names none. */
    private static List<Object> withTrigger(List<Object> behavior, String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return behavior;
        }
        var out = new ArrayList<Object>(behavior.size());
        for (Object entry : behavior) {
            if (entry instanceof Map<?, ?> rule && !(rule.get("on") instanceof String on && !on.isBlank())) {
                var withOn = new LinkedHashMap<String, Object>();
                withOn.put("on", trigger);
                rule.forEach((key, value) -> withOn.put(String.valueOf(key), value));
                out.add(withOn);
            } else {
                out.add(entry);
            }
        }
        return out;
    }
}
