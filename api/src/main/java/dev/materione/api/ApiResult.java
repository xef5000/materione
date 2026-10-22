package dev.materione.api;

import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.api.diagnostics.DiagnosticCode;
import java.util.List;
import java.util.function.Function;

/**
 * The fail-soft return wrapper for every {@code MaterioneAPI} call (api.md
 * §3.1): domain errors (syntax, revision conflicts {@code MTR-0034}, missing
 * files {@code MTR-0009}) are structured {@link Diagnostic} records with
 * stable {@code MTR-####} codes — API calls never throw unchecked exceptions
 * for them. Crash-class failures still propagate as exceptions; content
 * never takes the server down (D6), and neither does the API.
 *
 * @param ok          true when the operation produced its value
 * @param value       the operation's value; null on failure
 * @param diagnostics empty on a clean success, the reasons on failure,
 *                    warnings may ride a success
 */
public record ApiResult<T>(boolean ok, T value, List<Diagnostic> diagnostics) {

    public ApiResult {
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public static <T> ApiResult<T> ok(T value) {
        return new ApiResult<>(true, value, List.of());
    }

    public static <T> ApiResult<T> ok(T value, List<Diagnostic> diagnostics) {
        return new ApiResult<>(true, value, diagnostics);
    }

    public static <T> ApiResult<T> fail(List<Diagnostic> diagnostics) {
        return new ApiResult<>(false, null, diagnostics);
    }

    public static <T> ApiResult<T> fail(Diagnostic... diagnostics) {
        return fail(List.of(diagnostics));
    }

    public static <T> ApiResult<T> fail(DiagnosticCode code, String message) {
        return fail(Diagnostic.error(code, message));
    }

    /** Transforms the value on success; failures pass through untouched. */
    public <R> ApiResult<R> map(Function<T, R> mapper) {
        return ok ? new ApiResult<>(true, mapper.apply(value), diagnostics) : ApiResult.fail(diagnostics);
    }
}
