package dev.materione.plugin.logic.planted;

/** Fixture for the R11 proof: the violation, one action reaching into its sibling. */
public final class PlantedSecond {

    public Object reachIntoSibling() {
        return new PlantedFirst().parse();
    }
}
