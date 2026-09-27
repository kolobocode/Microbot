package net.runelite.client.plugins.microbot.humantiming;

import net.runelite.client.plugins.microbot.humanid.Factor;
import net.runelite.client.plugins.microbot.humanid.HumanId;
import net.runelite.client.plugins.microbot.humanid.Influence;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * One person's timing traits. {@link #fromName} derives them from the player's {@link HumanId}: same
 * name, same person, in every bot and on every platform that uses the derivation. Each trait sits
 * within ±5% of the population value and breathes slowly over time (see {@link Influence}).
 *
 * <p>Per-action randomness lives in {@link HumanTiming}; this class only says <i>whose</i> hands are
 * on the mouse.
 */
public final class HumanProfile {
    private final long seed;
    // --- per-action timing ---
    private final double speed;
    private final double spread;
    private final double reactionScale;
    private final double tailScale;
    private final double decisionPauseP;
    private final double autocorrelation;
    private final double afkiness;
    private final double fatiguePerHour;
    private final double practiceGain;
    private final Map<Action, Shape> shapes;
    // --- daily schedule ---
    private final double sleepStartHour;
    private final double sleepHours;
    private final double busyStartHour;
    private final double busyHours;
    private final double busyWeight;
    private final double eveningPeakHour;
    private final double dailyMinutes;
    private final double sessionMinutes;
    private final double sessionShape;
    private final double skipDayProbability;

    private HumanProfile(Builder b) {
        seed = b.seed;
        speed = b.speed;
        spread = b.spread;
        reactionScale = b.reactionScale;
        tailScale = b.tailScale;
        decisionPauseP = b.decisionPauseP;
        autocorrelation = b.autocorrelation;
        afkiness = b.afkiness;
        fatiguePerHour = b.fatiguePerHour;
        practiceGain = b.practiceGain;
        shapes = new EnumMap<>(b.shapes);
        sleepStartHour = b.sleepStartHour;
        sleepHours = b.sleepHours;
        busyStartHour = b.busyStartHour;
        busyHours = b.busyHours;
        busyWeight = b.busyWeight;
        eveningPeakHour = b.eveningPeakHour;
        dailyMinutes = b.dailyMinutes;
        sessionMinutes = b.sessionMinutes;
        sessionShape = b.sessionShape;
        skipDayProbability = b.skipDayProbability;
    }

    /** The population median person: every trait at its typical value. Mostly for tests. */
    public static HumanProfile average() {
        return new Builder(0).build();
    }

    /** Largest share any scaling trait moves away from the population value. */
    public static final double CAP = 0.05;
    /** Clock-time traits (sleep start, evening peak) move at most this many hours: 5% of a day. */
    public static final double CLOCK_CAP_HOURS = 1.2;

    /**
     * This player's profile right now: derived from their {@link HumanId}, so every bot and platform
     * that uses the same derivation gets the same person. Each trait breathes a little (see
     * {@link Influence}); build a fresh profile per session to pick that up.
     */
    public static HumanProfile fromName(String playerName) {
        return fromId(HumanId.of(playerName), System.currentTimeMillis(), new Random());
    }

    /** An identity without a name behind it, for tests and population simulations. Breathing frozen at t = 0. */
    public static HumanProfile fromSeed(long seed) {
        return fromId(HumanId.ofSeed(seed), 0, 0);
    }

    /** {@link #fromId(HumanId, long, Random)} with the random part of the breathing fixed at {@code r} in [-1, 1]. */
    public static HumanProfile fromId(HumanId id, long epochMs, double r) {
        return fromId(id, epochMs, fixedRandom(r));
    }

    /**
     * The population values (the Builder defaults), each moved by this player's influence: at most
     * {@link #CAP} either way for scaling traits, {@link #CLOCK_CAP_HOURS} for clock times. Traits lean
     * on shared {@link Factor}s so they hang together (a quick player is quick everywhere).
     */
    public static HumanProfile fromId(HumanId id, long t, Random rng) {
        Builder d = new Builder(id.seed());
        Builder b = new Builder(id.seed());
        b.speed = id.influence("timing.speed", Factor.TEMPO, -0.6, CAP).scale(d.speed, t, rng);
        b.spread = id.influence("timing.spread", Factor.STEADINESS, -0.5, CAP).scale(d.spread, t, rng);
        b.reactionScale = id.influence("timing.reaction", Factor.TEMPO, -0.5, CAP).scale(d.reactionScale, t, rng);
        b.tailScale = id.influence("timing.tail", Factor.RESTLESSNESS, 0.6, CAP).scale(d.tailScale, t, rng);
        b.decisionPauseP = id.influence("timing.decisionPause", Factor.CARE, 0.5, CAP).scale(d.decisionPauseP, t, rng);
        b.autocorrelation = id.influence("timing.rhythm", Factor.STEADINESS, 0.5, CAP).scale(d.autocorrelation, t, rng);
        b.afkiness = id.influence("timing.afkiness", Factor.RESTLESSNESS, 0.7, CAP).scale(d.afkiness, t, rng);
        b.fatiguePerHour = id.influence("timing.fatigue", CAP).scale(d.fatiguePerHour, t, rng);
        b.practiceGain = id.influence("timing.practice", CAP).scale(d.practiceGain, t, rng);
        b.sleepHours = id.influence("schedule.sleepHours", CAP).scale(d.sleepHours, t, rng);
        b.sleepStartHour = wrapHour(d.sleepStartHour + id.influence("schedule.sleepStart", CLOCK_CAP_HOURS).at(t, rng));
        b.busyStartHour = d.busyStartHour + id.influence("schedule.busyStart", CLOCK_CAP_HOURS).at(t, rng);
        b.busyHours = id.influence("schedule.busyHours", CAP).scale(d.busyHours, t, rng);
        b.busyWeight = id.influence("schedule.busyWeight", CAP).scale(d.busyWeight, t, rng);
        b.eveningPeakHour = d.eveningPeakHour + id.influence("schedule.eveningPeak", CLOCK_CAP_HOURS).at(t, rng);
        b.dailyMinutes = id.influence("schedule.dailyMinutes", Factor.RESTLESSNESS, -0.3, CAP).scale(d.dailyMinutes, t, rng);
        b.sessionMinutes = id.influence("schedule.sessionMinutes", CAP).scale(d.sessionMinutes, t, rng);
        b.sessionShape = id.influence("schedule.sessionShape", CAP).scale(d.sessionShape, t, rng);
        b.skipDayProbability = id.influence("schedule.skipDay", CAP).scale(d.skipDayProbability, t, rng);
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
        b.speed = speed;
        b.spread = spread;
        b.reactionScale = reactionScale;
        b.tailScale = tailScale;
        b.decisionPauseP = decisionPauseP;
        b.autocorrelation = autocorrelation;
        b.afkiness = afkiness;
        b.fatiguePerHour = fatiguePerHour;
        b.practiceGain = practiceGain;
        b.shapes.putAll(shapes);
        b.sleepStartHour = sleepStartHour;
        b.sleepHours = sleepHours;
        b.busyStartHour = busyStartHour;
        b.busyHours = busyHours;
        b.busyWeight = busyWeight;
        b.eveningPeakHour = eveningPeakHour;
        b.dailyMinutes = dailyMinutes;
        b.sessionMinutes = sessionMinutes;
        b.sessionShape = sessionShape;
        b.skipDayProbability = skipDayProbability;
        return b;
    }

    /** The distribution for {@code action}: a fitted override if set, otherwise the population default. */
    public Shape shape(Action action) {
        Shape s = shapes.get(action);
        return s != null ? s : action.defaultShape;
    }

    public long seed() { return seed; }
    /** Tempo multiplier on self-paced gaps (1 = typical, 1.2 = 20% slower). */
    public double speed() { return speed; }
    /** Multiplier on the spread of self-paced gaps (1 = typical). */
    public double spread() { return spread; }
    /** Multiplier on reaction times to game events. */
    public double reactionScale() { return reactionScale; }
    /** Multiplier on the chance of a long 4-35 s pause. */
    public double tailScale() { return tailScale; }
    /** Chance of an extra 8-13 s think at a decision point. */
    public double decisionPauseP() { return decisionPauseP; }
    /** Lag-1 correlation of consecutive gaps of the same kind (people fall into a rhythm). */
    public double autocorrelation() { return autocorrelation; }
    /** Long-run fraction of time spent distracted (second screen, chat, half-AFK). */
    public double afkiness() { return afkiness; }
    /** Fractional slow-down per hour of continuous play. */
    public double fatiguePerHour() { return fatiguePerHour; }
    /** How much slower the first repetition of a new task is (0.25 = 25%), decaying as a power law. */
    public double practiceGain() { return practiceGain; }
    public double sleepStartHour() { return sleepStartHour; }
    public double sleepHours() { return sleepHours; }
    /** Weekday work/school block start (hour of day). */
    public double busyStartHour() { return busyStartHour; }
    public double busyHours() { return busyHours; }
    /** Relative activity during the busy block (0 = never plays at work, 1 = no difference). */
    public double busyWeight() { return busyWeight; }
    public double eveningPeakHour() { return eveningPeakHour; }
    /** Median minutes played on a day that is played at all. */
    public double dailyMinutes() { return dailyMinutes; }
    /** Weibull scale of one sitting's length, in minutes (the median is ~0.6x this at shape 0.75). */
    public double sessionMinutes() { return sessionMinutes; }
    /** Weibull shape of sitting lengths; below 1 means many short sittings and a long tail. */
    public double sessionShape() { return sessionShape; }
    public double skipDayProbability() { return skipDayProbability; }

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "HumanProfile{speed=%.2f spread=%.2f reaction=%.2f tail=%.2f decisionPause=%.3f rho=%.2f afk=%.2f "
                        + "fatigue=%.2f/h practice=%.2f sleep=%.1fh+%.1fh evening=%.1fh daily=%.0fmin session=%.0fmin/k%.2f "
                        + "skipDay=%.2f overrides=%s}",
                speed, spread, reactionScale, tailScale, decisionPauseP, autocorrelation, afkiness,
                fatiguePerHour, practiceGain, sleepStartHour, sleepHours, eveningPeakHour, dailyMinutes,
                sessionMinutes, sessionShape, skipDayProbability, shapes.keySet());
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double wrapHour(double h) {
        return ((h % 24) + 24) % 24;
    }

    /** Mutable copy of a profile. Start from {@link HumanProfile#toBuilder()} or {@code new Builder(seed)}. */
    public static final class Builder {
        private final long seed;
        private double speed = 1;
        private double spread = 1;
        private double reactionScale = 1;
        private double tailScale = 1;
        private double decisionPauseP = 0.08;
        private double autocorrelation = 0.22;
        private double afkiness = 0.2;
        private double fatiguePerHour = 0.017;
        private double practiceGain = 0.25;
        private final Map<Action, Shape> shapes = new EnumMap<>(Action.class);
        private double sleepStartHour = 0.45;
        private double sleepHours = 7.6;
        private double busyStartHour = 8.3;
        private double busyHours = 8.5;
        private double busyWeight = 0.15;
        private double eveningPeakHour = 19.5;
        private double dailyMinutes = 150;
        private double sessionMinutes = 65;
        private double sessionShape = 0.75;
        private double skipDayProbability = 0.2;

        public Builder(long seed) {
            this.seed = seed;
        }

        /** Replace the population default for one action, e.g. with a {@link TimingFit} of your own play. */
        public Builder shape(Action action, Shape shape) { shapes.put(action, shape); return this; }
        public Builder speed(double v) { speed = v; return this; }
        public Builder spread(double v) { spread = v; return this; }
        public Builder reactionScale(double v) { reactionScale = v; return this; }
        public Builder tailScale(double v) { tailScale = v; return this; }
        public Builder decisionPauseP(double v) { decisionPauseP = v; return this; }
        public Builder autocorrelation(double v) { autocorrelation = clamp(v, 0, 0.95); return this; }
        public Builder afkiness(double v) { afkiness = clamp(v, 0, 0.9); return this; }
        public Builder fatiguePerHour(double v) { fatiguePerHour = v; return this; }
        public Builder practiceGain(double v) { practiceGain = v; return this; }
        public Builder sleepStartHour(double v) { sleepStartHour = wrapHour(v); return this; }
        public Builder sleepHours(double v) { sleepHours = v; return this; }
        public Builder busyStartHour(double v) { busyStartHour = v; return this; }
        public Builder busyHours(double v) { busyHours = v; return this; }
        public Builder busyWeight(double v) { busyWeight = v; return this; }
        public Builder eveningPeakHour(double v) { eveningPeakHour = v; return this; }
        public Builder dailyMinutes(double v) { dailyMinutes = v; return this; }
        public Builder sessionMinutes(double v) { sessionMinutes = v; return this; }
        public Builder sessionShape(double v) { sessionShape = v; return this; }
        public Builder skipDayProbability(double v) { skipDayProbability = v; return this; }

        public HumanProfile build() {
            return new HumanProfile(this);
        }
    }
}
