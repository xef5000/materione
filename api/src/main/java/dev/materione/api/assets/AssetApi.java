package dev.materione.api.assets;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import java.util.List;

/**
 * Binary asset management (api.md §3.4, web.md §4.2/§4.3): textures, models
 * and sounds under {@code content/packs/<pack>/assets/}. Writes are
 * size-capped, path-sanitized, atomic, audited, and followed by a pack
 * reload (D6). Authoring metadata under {@code .materione/} is readable
 * (thumbnails) but only writable through the transaction that produces it
 * ({@code saveModelTransaction}) — never by a free-form upload.
 */
public interface AssetApi {

    /** Assets of one pack; query is a case-insensitive substring filter, null lists all. */
    ApiResult<List<AssetInfo>> listAssets(String pack, String query);

    /** Reads one asset's bytes ({@code MTR-0009} when missing). */
    ApiResult<byte[]> getAsset(String pack, String path);

    /**
     * Atomic size-capped write; returns the byte count written. Caps: 2 MiB
     * per asset, 8 MiB for audio ({@code .ogg}/.{@code oga}).
     */
    ApiResult<Integer> putAsset(ActorContext actor, String pack, String path, byte[] bytes);

    /** Atomic rename; the target must not exist. */
    ApiResult<Void> moveAsset(ActorContext actor, String pack, String fromPath, String toPath);

    ApiResult<Void> deleteAsset(ActorContext actor, String pack, String path);

    // ------------------------------------------------------------------

    /** One asset file: path relative to the pack root, size, and kind (extension). */
    record AssetInfo(String path, long sizeBytes, String kind) {}
}
