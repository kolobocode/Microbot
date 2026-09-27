package net.runelite.client.plugins.microbot.humanid;

/**
 * Shared personality factors. A trait can lean on one, so a player who is quick with the mouse is
 * also quick to react, instead of every trait being an independent dice roll.
 */
public enum Factor {
    /** General pace: think time, reactions, mouse speed, dwell before a press. */
    TEMPO("tempo"),
    /** Care over speed: click accuracy, first-stroke accuracy, extra thought at decisions. */
    CARE("care"),
    /** Consistency: spread of gaps, rhythm, habitual click spots, path wobble. */
    STEADINESS("steadiness"),
    /** Restlessness: distraction, long pauses, fidgeting with the mouse while waiting. */
    RESTLESSNESS("restlessness");

    final String key;

    Factor(String key) {
        this.key = key;
    }
}
