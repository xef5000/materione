package dev.materione.core.assets;

import dev.materione.api.ApiResult;
import dev.materione.api.assets.AssetApi;
import dev.materione.api.auth.ActorContext;
import dev.materione.api.diagnostics.Diagnostic;
import dev.materione.core.authoring.ReloadHook;
import dev.materione.core.diagnostics.DiagnosticCodes;
import dev.materione.core.document.AssetStore;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@link AssetApi} implementation over {@link AssetStore} (api.md §3.4):
 * list, read, write, move and delete pack-binary assets with the store's
 * caps, path rules, atomic writes and audit entries. Every mutation fires
 * the {@link ReloadHook} with the pack root — a whole-pack preview reload,
 * because a texture edit can affect any referencing item.
 */
public final class AssetApiImpl implements AssetApi {

    private final AssetStore store;
    private final Path packsRoot;
    private final ReloadHook reloadHook;

    public AssetApiImpl(AssetStore store, Path packsRoot, ReloadHook reloadHook) {
        this.store = Objects.requireNonNull(store, "store");
        this.packsRoot = Objects.requireNonNull(packsRoot, "packsRoot");
        this.reloadHook = reloadHook == null ? (actor, pack, file) -> { } : reloadHook;
    }

    @Override
    public ApiResult<List<AssetInfo>> listAssets(String pack, String query) {
        try {
            List<AssetInfo> assets = new ArrayList<>();
            String needle = query == null ? null : query.toLowerCase(Locale.ROOT);
            for (AssetStore.AssetEntry entry : store.list(pack)) {
                if (needle == null || entry.path().toLowerCase(Locale.ROOT).contains(needle)) {
                    assets.add(new AssetInfo(entry.path(), entry.sizeBytes(), entry.kind()));
                }
            }
            return ApiResult.ok(assets);
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<byte[]> getAsset(String pack, String path) {
        try {
            return ApiResult.ok(store.get(pack, path));
        } catch (NoSuchFileException e) {
            return new ApiResult<byte[]>(false, null, List.of(Diagnostic.error(
                    DiagnosticCodes.UNRESOLVED_REFERENCE, "no such asset")));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Integer> putAsset(ActorContext actor, String pack, String path, byte[] bytes) {
        try {
            Objects.requireNonNull(bytes, "bytes");
            int written = store.put(actorId(actor), pack, path, bytes);
            reloadHook.afterChange(actor, pack, packsRoot.resolve(pack));
            return ApiResult.ok(written);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Void> moveAsset(ActorContext actor, String pack, String fromPath, String toPath) {
        try {
            store.move(actorId(actor), pack, fromPath, toPath);
            reloadHook.afterChange(actor, pack, packsRoot.resolve(pack));
            return ApiResult.ok(null);
        } catch (NoSuchFileException e) {
            return new ApiResult<Void>(false, null, List.of(Diagnostic.error(
                    DiagnosticCodes.UNRESOLVED_REFERENCE, "no such asset: " + e.getFile())));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    @Override
    public ApiResult<Void> deleteAsset(ActorContext actor, String pack, String path) {
        try {
            boolean existed = store.delete(actorId(actor), pack, path);
            if (existed) {
                reloadHook.afterChange(actor, pack, packsRoot.resolve(pack));
            }
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            return handlerFailure(e);
        }
    }

    // ------------------------------------------------------------------

    private static String actorId(ActorContext actor) {
        return Objects.requireNonNull(actor, "actor").actorId();
    }

    private static <T> ApiResult<T> badRequest(String message) {
        return new ApiResult<T>(false, null, List.of(Diagnostic.error(
                DiagnosticCodes.CONTENT_SCHEMA_INVALID, message)));
    }

    private static <T> ApiResult<T> handlerFailure(Exception e) {
        return new ApiResult<T>(false, null, List.of(Diagnostic.error(
                DiagnosticCodes.PIPELINE_STAGE_FAILED, String.valueOf(e.getMessage()))));
    }
}
