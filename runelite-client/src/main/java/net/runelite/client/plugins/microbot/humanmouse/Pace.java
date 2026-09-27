package net.runelite.client.plugins.microbot.humanmouse;

/**
 * How much care the player puts into a click. Speed and accuracy trade off: hurrying mostly widens
 * the spread along the direction of movement and raises misses (Yamanaka and MacKenzie 2026:
 * 1.3% accurate, 3.2% neutral, 7.7% fast, 152k clicks).
 */
public enum Pace {
    /** Something that must not go wrong: a bank withdraw-X, casting on a specific NPC, a trade. */
    CAREFUL(0.85, 1.20),
    /** The default for routine clicks. */
    NORMAL(1.00, 1.00),
    /** Fast repeats: dropping a full inventory, spam-clicking the next rock, prayer flicking. */
    HURRIED(1.20, 0.85);

    /** Multiplier on the click spread. */
    final double spread;
    /** Multiplier on movement time: careful is slower, hurried faster. */
    final double time;

    Pace(double spread, double time) {
        this.spread = spread;
        this.time = time;
    }
}
