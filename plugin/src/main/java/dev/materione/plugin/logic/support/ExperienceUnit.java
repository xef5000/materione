package dev.materione.plugin.logic.support;

/** The unit of an experience action's amount: total points, or whole levels (logic.md §1, exp rows). */
public enum ExperienceUnit {
    POINTS,
    LEVELS;

    /** The largest grant or take in one step: far beyond any reward, and inside what the server stores. */
    public static final int MAX_AMOUNT = 1_000_000;
}
