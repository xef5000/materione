package dev.materione.plugin.logic.planted;

/** Fixture for the R11 proof: an "action" with a nested parameter record, depending on nothing else. */
public final class PlantedFirst {

    /** Its own parameter type; a class's nested types are part of it. */
    public record Params(String value) {}

    public Params parse() {
        return new Params("x");
    }
}
