package net.runelite.client.plugins.microbot.humanid;

import java.util.Locale;
import java.util.Random;

/**
 * How much one trait of one player differs from the population value, and how that difference
 * breathes over time.
 *
 * <pre>
 * value(t) = cap * s * (1 + 0.10 * r + 0.05 * sin(2 pi t / P + phi)) / 1.15
 * </pre>
 *
 * <ul>
 *   <li>{@code s} in (-1, 1): the player's fixed standing on this trait (tanh of a normal draw).</li>
 *   <li>{@code r} in [-1, 1]: fresh every time the value is read ({@link #at(long, Random)}).</li>
 *   <li>A slow sine with this player's own period {@code P} (7-30 days) and phase: the same on every
 *       machine at the same moment.</li>
 * </ul>
 * The division keeps |value| &lt;= cap even when both breathing parts peak together.
 */
public final class Influence {
    public static final double RANDOM_SHARE = 0.10;
    public static final double TIME_SHARE = 0.05;
    static final double MIN_PERIOD_DAYS = 7;
    static final double MAX_PERIOD_DAYS = 30;
    private static final double DAY_MS = 86_400_000.0;

    private final String trait;
    private final double strength;
    private final double cap;
    private final double periodDays;
    private final double phase;

    Influence(String trait, double strength, double cap, double periodDays, double phase) {
        this.trait = trait;
        this.strength = strength;
        this.cap = cap;
        this.periodDays = periodDays;
        this.phase = phase;
    }

    /** The fixed part, without breathing. */
    public double fixed() {
        return cap * strength / (1 + RANDOM_SHARE + TIME_SHARE);
    }

    /** The value at {@code epochMs} with the random part {@code r} in [-1, 1]. */
    public double at(long epochMs, double r) {
        double wave = Math.sin(2 * Math.PI * (epochMs / DAY_MS) / periodDays + phase);
        return cap * strength * (1 + RANDOM_SHARE * r + TIME_SHARE * wave) / (1 + RANDOM_SHARE + TIME_SHARE);
    }

    /** The value at {@code epochMs} with a fresh random part. */
    public double at(long epochMs, Random rng) {
        return at(epochMs, 2 * rng.nextDouble() - 1);
    }

    /** {@code base * (1 + value)}: for traits that scale. */
    public double scale(double base, long epochMs, Random rng) {
        return base * (1 + at(epochMs, rng));
    }

    public String trait() { return trait; }
    /** Fixed standing in (-1, 1). */
    public double strength() { return strength; }
    public double cap() { return cap; }
    public double periodDays() { return periodDays; }
    public double phase() { return phase; }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s %+.2f%% (cap %.1f%%, period %.1f d)", trait, 100 * fixed(), 100 * cap, periodDays);
    }
}
