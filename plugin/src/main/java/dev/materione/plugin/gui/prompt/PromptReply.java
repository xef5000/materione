package dev.materione.plugin.gui.prompt;

/**
 * How a prompt ended, as the step that asked it sees it (logic.md §6.6): the player answered, or they did not. An
 * unanswered prompt covers a decline, a cancel word, a timeout, a quit, and a method that could not start.
 */
public sealed interface PromptReply permits PromptReply.Answer, PromptReply.Unanswered {

    /**
     * The player's answer.
     *
     * @param text the answer, trimmed and never blank
     */
    record Answer(String text) implements PromptReply {}

    /**
     * No answer.
     *
     * @param reason a short sentence the trace and the player can read
     */
    record Unanswered(String reason) implements PromptReply {}
}
