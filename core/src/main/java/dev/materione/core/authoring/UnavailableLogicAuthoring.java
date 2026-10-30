package dev.materione.core.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.logic.LogicAuthoringApi;
import java.util.List;
import java.util.Map;

/**
 * The logic surface of a build that carries no behavior engine (core alone, or a test wiring without
 * it). Every call fails soft with one diagnostic, so a caller sees the gap at the call site.
 */
final class UnavailableLogicAuthoring implements LogicAuthoringApi {

    private static final String MESSAGE = "logic authoring is not wired in this build";

    private static <T> ApiResult<T> unavailable() {
        return ApiResult.fail(Diagnostic.error(DiagnosticCodes.PIPELINE_STAGE_FAILED, MESSAGE));
    }

    @Override
    public ApiResult<LogicView> get(String pack, String path) {
        return unavailable();
    }

    @Override
    public ApiResult<LogicView> edit(ActorContext actor, LogicEditRequest request) {
        return unavailable();
    }

    @Override
    public ApiResult<List<Diagnostic>> validate(LogicDraft draft) {
        return unavailable();
    }

    @Override
    public ApiResult<List<Map<String, Object>>> instantiate(InstantiateRequest request) {
        return unavailable();
    }
}
