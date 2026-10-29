package dev.materione.core.authoring;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCodes;
import dev.materione.api.gui.GuiAuthoringApi;
import java.util.List;

/**
 * The GUI surface of a build that carries no GUI compiler (core alone, or a test wiring
 * without the content module). Every call fails soft with one diagnostic instead of throwing,
 * so a caller sees the gap at the call site and the rest of the API keeps working.
 */
final class UnavailableGuiAuthoring implements GuiAuthoringApi {

    private static final String MESSAGE = "GUI authoring is not wired in this build";

    private static <T> ApiResult<T> unavailable() {
        return ApiResult.fail(Diagnostic.error(DiagnosticCodes.PIPELINE_STAGE_FAILED, MESSAGE));
    }

    @Override
    public List<GuiComponentDescriptor> components() {
        return List.of();
    }

    @Override
    public ApiResult<GuiDocumentView> get(String pack, String path) {
        return unavailable();
    }

    @Override
    public ApiResult<GuiDocumentView> edit(ActorContext actor, GuiEditRequest request) {
        return unavailable();
    }

    @Override
    public ApiResult<CustomizeItemResult> customizeItem(ActorContext actor, CustomizeItemRequest request) {
        return unavailable();
    }

    @Override
    public ApiResult<GuiRender> render(GuiRenderRequest request) {
        return unavailable();
    }

    @Override
    public ApiResult<List<Diagnostic>> validate(GuiValidateRequest request) {
        return unavailable();
    }
}
