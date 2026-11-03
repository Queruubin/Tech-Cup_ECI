package edu.escuelaing.techcup.competition.domain;

/**
 * The side of the next knockout match a match feeds: its winner becomes the HOME team (the first
 * match of each pair) or the AWAY team (the second one) of that match.
 */
public enum BracketSlot {
    HOME,
    AWAY;

    /** The side fed by the match at {@code index} (0-based, bracket order) of its round. */
    public static BracketSlot forIndex(int index) {
        return index % 2 == 0 ? HOME : AWAY;
    }
}
