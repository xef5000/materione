package dev.materione.plugin.escrow.plantedowner;

import dev.materione.plugin.escrow.io.EscrowLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Fixture for the ban proof: an owner-side class that reads a file, blocks on a future and awaits a preload. */
public final class PlantedOwnerHalf {

    public void violate(Path file, EscrowLedgerStore store, UUID player) throws Exception {
        Files.readAllBytes(file);
        CompletableFuture.completedFuture(1).get();
        CompletableFuture.completedFuture(2).join();
        new java.util.concurrent.CountDownLatch(0).await();
        store.awaitPreload(player, Duration.ofSeconds(1));
    }
}
