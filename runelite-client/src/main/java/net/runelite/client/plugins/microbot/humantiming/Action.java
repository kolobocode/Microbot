package net.runelite.client.plugins.microbot.humantiming;

/**
 * The kinds of pause a player makes. Each value is <b>think time</b>: from the moment the player could
 * act until they start the next input. Mouse travel is not included, because Microbot's natural mouse
 * already spends that time; use {@link HumanTiming#clickGap} when the mouse moves instantly.
 *
 * <p>Defaults are population medians from the reaction-time and HCI literature. Replace them with
 * your own fitted values ({@link TimingFit}) through {@link HumanProfile.Builder#shape}.
 */
public enum Action {
    /**
     * Next input in a fast run of the same thing: dropping the next item, clicking the next inventory
     * slot, eating food in a row. Rhythmic, tight spread.
     */
    SEQUENCE(Shape.lognormal(40, 130, 0.40), 1.15),

    /**
     * Next step of a routine you know by heart: tinderbox then log, deposit then withdraw, open bank
     * then click the item. Slightly more thought than a sequence.
     */
    ROUTINE(Shape.lognormal(80, 380, 0.45), 1.4),

    /**
     * Reacting to an expected game event you were waiting for: bank interface opened, fire lit, you
     * stopped walking, the rock you watched respawned. Ex-Gaussian, mean 430 ms when focused: between
     * measured 4-choice RT (388 ms, Deary et al. 2011) and a slower visual search in a busy scene,
     * with tau above the 71 ms of a 10-minute vigilance task (Unsworth et al. 2010) because the
     * task is long and monotonous.
     */
    REACT(Shape.exGaussian(310, 40, 120), 2.4),

    /**
     * A decision point: choosing the next row, picking a new tree, after banking, after a level-up.
     * Also carries a separate ~8% chance of an 8-13 s think (see {@link HumanTiming#DECISION_PAUSE_P}).
     */
    DECISION(Shape.lognormal(250, 1100, 0.55), 2.2);

    final Shape defaultShape;
    /** Median multiplier while the player is distracted (second screen, chatting, half-AFK). */
    final double distractedFactor;

    Action(Shape defaultShape, double distractedFactor) {
        this.defaultShape = defaultShape;
        this.distractedFactor = distractedFactor;
    }

    public Shape defaultShape() {
        return defaultShape;
    }
}
