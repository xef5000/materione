package dev.materione.plugin.escrow.planted;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Fixture for the purity proof: a model class that does IO, reads the clock and blocks on a future. */
public final class PlantedImpure {

    public long violate(Path file) throws Exception {
        long when = System.currentTimeMillis();
        Files.readAllBytes(file);
        CompletableFuture.completedFuture(when).get();
        return when;
    }

    public void bukkit() {
        org.bukkit.Bukkit.getName();
    }
}
