package net.runelite.client.plugins.microbot.humanmouse;

import net.runelite.client.plugins.microbot.humanid.Factor;
import net.runelite.client.plugins.microbot.humanid.HumanId;
import net.runelite.client.plugins.microbot.humanid.Influence;

import java.util.Locale;
import java.util.Random;

/**
 * One person's mouse hand, derived from their {@link HumanId} (shared with HumanTiming), so the same
 * player has the same hand everywhere. Each trait sits within ±5% of the population value and
 * breathes slowly over time.
 *
 * <p>The defaults describe a practised gamer: faster and more accurate than the non-gamers in
 * pointing studies (see README, "Gamer calibration").
 */
public final class MouseProfile {
    private final long seed;
    private final double accuracy;
    private final double biasX;
    private final double biasY;
    private final double habit;
    private final double fittsA;
    private final double fittsB;
    private final double reach;
    private final double primaryError;
    private final double strokeSigma;
    private final double arc;
    private final double wanderPx;
    private final double dwellMedianMs;
    private final double holdMeanMs;
    private final double pollMs;
    private final double stillShare;

    private MouseProfile(Builder b) {
        seed = b.seed;
        accuracy = b.accuracy;
        biasX = b.biasX;
        biasY = b.biasY;
        habit = b.habit;
        fittsA = b.fittsA;
        fittsB = b.fittsB;
        reach = b.reach;
        primaryError = b.primaryError;
        strokeSigma = b.strokeSigma;
        arc = b.arc;
        wanderPx = b.wanderPx;
        dwellMedianMs = b.dwellMedianMs;
        holdMeanMs = b.holdMeanMs;
        pollMs = b.pollMs;
        stillShare = b.stillShare;
    }

    /** The typical practised player: every trait at its population median. Mostly for tests. */
    public static MouseProfile average() {
        return new Builder(0).build();
    }

    /** Largest share any scaling trait moves away from the population value. */
    public static final double CAP = 0.05;
    /** Click offset: at most 5% of the target's size either way. */
    public static final double BIAS_CAP = 0.05;
    /** First-stroke reach moves at most 1%: it sets the overshoot rate, which is sensitive. */
    public static final double REACH_CAP = 0.01;

    /**
     * This player's mouse hand right now, from their {@link HumanId}: the same hand in every bot and
     * on every platform using the derivation, breathing a little over time (see {@link Influence}).
     */
    public static MouseProfile fromName(String playerName) {
        return fromId(HumanId.of(playerName), System.currentTimeMillis(), new Random());
    }

    /** An identity without a name behind it, for tests and population simulations. Breathing frozen at t = 0. */
    public static MouseProfile fromSeed(long seed) {
        return fromId(HumanId.ofSeed(seed), 0, fixedRandom(0));
    }

    /**
     * The population values (the Builder defaults), each moved by this player's influence, leaning on
     * the same shared {@link Factor}s as HumanTiming: a player with a quick tempo there is quick here.
     */
    public static MouseProfile fromId(HumanId id, long t, Random rng) {
        Builder d = new Builder(id.seed());
        Builder b = new Builder(id.seed());
        b.accuracy = id.influence("mouse.accuracy", Factor.CARE, -0.6, CAP).scale(d.accuracy, t, rng);
        b.biasX = id.influence("mouse.biasX", BIAS_CAP).at(t, rng);
        b.biasY = id.influence("mouse.biasY", BIAS_CAP).at(t, rng);
        b.habit = id.influence("mouse.habit", Factor.STEADINESS, 0.5, CAP).scale(d.habit, t, rng);
        b.fittsA = id.influence("mouse.fittsA", Factor.TEMPO, -0.5, CAP).scale(d.fittsA, t, rng);
        b.fittsB = id.influence("mouse.fittsB", Factor.TEMPO, -0.6, CAP).scale(d.fittsB, t, rng);
        b.reach = id.influence("mouse.reach", Factor.CARE, 0.3, REACH_CAP).scale(d.reach, t, rng);
        b.primaryError = id.influence("mouse.primaryError", Factor.CARE, -0.5, CAP).scale(d.primaryError, t, rng);
        b.strokeSigma = id.influence("mouse.strokeSigma", CAP).scale(d.strokeSigma, t, rng);
        b.arc = id.influence("mouse.arc", CAP).scale(d.arc, t, rng);
        b.wanderPx = id.influence("mouse.wander", Factor.STEADINESS, -0.4, CAP).scale(d.wanderPx, t, rng);
        b.dwellMedianMs = id.influence("mouse.dwell", Factor.TEMPO, -0.5, CAP).scale(d.dwellMedianMs, t, rng);
        b.holdMeanMs = id.influence("mouse.hold", CAP).scale(d.holdMeanMs, t, rng);
        b.stillShare = id.influence("mouse.stillShare", Factor.RESTLESSNESS, -0.6, CAP).scale(d.stillShare, t, rng);
        // Hardware, not the hand: a fixed polling rate per player (no breathing).
        double u = id.unit("mouse.poll");
        b.pollMs = u < 0.45 ? 8 : u < 0.65 ? 4 : u < 0.80 ? 2 : 1;
        return b.build();
    }

    /** A Random whose nextDouble() always maps to {@code r} in [-1, 1] (for frozen breathing). */
    static Random fixedRandom(double r) {
        return new Random() {
            private static final long serialVersionUID = 1L;

            @Override
            public double nextDouble() {
                return (r + 1) / 2;
            }
        };
    }

    public Builder toBuilder() {
        Builder b = new Builder(seed);
        b.accuracy = accuracy;
        b.biasX = biasX;
        b.biasY = biasY;
        b.habit = habit;
        b.fittsA = fittsA;
        b.fittsB = fittsB;
        b.reach = reach;
        b.primaryError = primaryError;
        b.strokeSigma = strokeSigma;
        b.arc = arc;
        b.wanderPx = wanderPx;
        b.dwellMedianMs = dwellMedianMs;
        b.holdMeanMs = holdMeanMs;
        b.pollMs = pollMs;
        b.stillShare = stillShare;
        return b;
    }

    public long seed() { return seed; }
    /** Multiplier on the crowd-study click spread (0.7 = a practised player, 1 = a crowdworker). */
    public double accuracy() { return accuracy; }
    /** Where this player's clicks centre, as a fraction of target width right of the aim point. */
    public double biasX() { return biasX; }
    /** As {@link #biasX()}, fraction of target height below the aim point. */
    public double biasY() { return biasY; }
    /** How strongly clicks on a familiar target keep to a personal spot (share of the spread, at most). */
    public double habit() { return habit; }
    /** Fitts' law intercept, ms. */
    public double fittsA() { return fittsA; }
    /** Fitts' law slope, ms per bit. */
    public double fittsB() { return fittsB; }
    /** Mean share of the distance the first stroke covers (below 1: people aim a little short). */
    public double reach() { return reach; }
    /** Multiplier on the first stroke's endpoint error (Grossman and Balakrishnan 2005 is 1). */
    public double primaryError() { return primaryError; }
    /** Log-normal sigma of the first stroke's speed profile; larger means an earlier, sharper peak. */
    public double strokeSigma() { return strokeSigma; }
    /** Typical sideways bow of a horizontal move, as a fraction of its length. */
    public double arc() { return arc; }
    /** Size of the slow sideways wander on long moves, px. */
    public double wanderPx() { return wanderPx; }
    /** Median time from the cursor settling to the button going down, ms. */
    public double dwellMedianMs() { return dwellMedianMs; }
    /** Mean button hold, ms. */
    public double holdMeanMs() { return holdMeanMs; }
    /** Mouse report interval, ms (125 Hz = 8, 1000 Hz = 1). */
    public double pollMs() { return pollMs; }
    /** Share of waiting time the hand rests without moving the mouse. */
    public double stillShare() { return stillShare; }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "MouseProfile{accuracy=%.2f bias=(%.3f,%.3f) habit=%.2f fitts=%.0f+%.0f/bit reach=%.3f primaryErr=%.2f "
                        + "strokeSigma=%.2f arc=%.3f wander=%.1fpx dwell=%.0fms hold=%.0fms poll=%.0fms still=%.2f}",
                accuracy, biasX, biasY, habit, fittsA, fittsB, reach, primaryError, strokeSigma, arc, wanderPx,
                dwellMedianMs, holdMeanMs, pollMs, stillShare);
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Mutable copy of a profile. Start from {@link MouseProfile#toBuilder()} or {@code new Builder(seed)}. */
    public static final class Builder {
        private final long seed;
        private double accuracy = 0.70;
        private double biasX = 0;
        private double biasY = 0;
        private double habit = 0.22;
        private double fittsA = 115;
        private double fittsB = 84;
        private double reach = 0.95;
        private double primaryError = 0.85;
        private double strokeSigma = 0.21;
        private double arc = 0.035;
        private double wanderPx = 0.8;
        private double dwellMedianMs = 45;
        private double holdMeanMs = 105;
        private double pollMs = 8;
        private double stillShare = 0.62;

        public Builder(long seed) {
            this.seed = seed;
        }

        public Builder accuracy(double v) { accuracy = v; return this; }
        public Builder biasX(double v) { biasX = v; return this; }
        public Builder biasY(double v) { biasY = v; return this; }
        public Builder habit(double v) { habit = clamp(v, 0, 0.9); return this; }
        public Builder fittsA(double v) { fittsA = v; return this; }
        public Builder fittsB(double v) { fittsB = v; return this; }
        public Builder reach(double v) { reach = v; return this; }
        public Builder primaryError(double v) { primaryError = v; return this; }
        public Builder strokeSigma(double v) { strokeSigma = clamp(v, 0.1, 0.5); return this; }
        public Builder arc(double v) { arc = v; return this; }
        public Builder wanderPx(double v) { wanderPx = v; return this; }
        public Builder dwellMedianMs(double v) { dwellMedianMs = v; return this; }
        public Builder holdMeanMs(double v) { holdMeanMs = v; return this; }
        public Builder pollMs(double v) { pollMs = Math.max(0.5, v); return this; }
        public Builder stillShare(double v) { stillShare = clamp(v, 0, 0.95); return this; }

        public MouseProfile build() {
            return new MouseProfile(this);
        }
    }
}
