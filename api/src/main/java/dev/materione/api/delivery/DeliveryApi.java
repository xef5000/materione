package dev.materione.api.delivery;

import dev.materione.api.ApiResult;
import dev.materione.api.auth.ActorContext;
import java.util.UUID;

/**
 * Pack hosting and release promotion (api.md §3.6, master §8.4): the
 * candidate/published channel. Promotion is explicit and hash-exact — only
 * the candidate the author previewed can be published; a newer candidate
 * rejects the promotion. Publication moves a version pointer and sends to
 * online players; it never recompiles or substitutes newer bytes.
 */
public interface DeliveryApi {

    /** The release channel state. */
    DeliveryStatus status();

    /**
     * Promotes the previewed candidate hash to published and distributes to
     * online players ({@code MTR-0034} when the candidate moved).
     */
    ApiResult<Void> publishCandidate(ActorContext actor, String expectedCandidateSha256);

    /** Sends the published or candidate pack to one player. */
    ApiResult<Void> sendPack(ActorContext actor, UUID player, PackTarget target);

    // ------------------------------------------------------------------

    /**
     * @param publishedSha256 the durable published version ({@code storage/}),
     *                        null when nothing is published
     * @param candidateSha256 the active candidate, null when none is staged
     * @param packUrl         the versioned pack URL, null when not running
     * @param running         whether the delivery backend is serving
     */
    record DeliveryStatus(
            String publishedSha256, String candidateSha256, String packUrl, boolean running) {}

    /** Which version {@link #sendPack} sends. */
    enum PackTarget { PUBLISHED, CANDIDATE }
}
