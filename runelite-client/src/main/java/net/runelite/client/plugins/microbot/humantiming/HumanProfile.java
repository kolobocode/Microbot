package net.runelite.client.plugins.microbot.humantiming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * One person's timing traits. Response-time parameters differ a lot between individuals, so every
 * player gets their own: {@link #fromName} derives a stable profile from the in-game name (same name,
 * same person, every run), {@link #fromSeed} from any number.
 *
 * <p>Traits are drawn once from population spreads and then held fixed. Per-action randomness lives
 * in {@link HumanTiming}; this class only says <i>whose</i> hands are on the mouse.
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

    /** A stable profile for an in-game name (case and surrounding spaces ignored). */
    public static HumanProfile fromName(String playerName) {
        String key = "humantiming/v1/" + playerName.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) seed = (seed << 8) | (h[i] & 0xff);
            return fromSeed(seed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Draws every trait from its population spread using {@code seed}. */
    public static HumanProfile fromSeed(long seed) {
        Random r = new Random(seed);
        Builder b = new Builder(seed);
        // Individual differences: a person is consistently faster/slower and steadier/sloppier.
        b.speed = clamp(Math.exp(0.15 * r.nextGaussian()), 0.7, 1.45);
        b.spread = clamp(Math.exp(0.15 * r.nextGaussian()), 0.75, 1.35);
        b.reactionScale = clamp(Math.exp(0.12 * r.nextGaussian()), 0.75, 1.35);
        b.tailScale = clamp(Math.exp(0.5 * r.nextGaussian()), 0.4, 2.5);
        b.decisionPauseP = 0.08 * clamp(Math.exp(0.3 * r.nextGaussian()), 0.5, 1.6);
        // Lag-1 correlation of response series is ~0.2 (Wagenmakers et al. 2004); the tempo drift adds ~0.1.
        b.autocorrelation = 0.10 + 0.25 * r.nextDouble();
        b.afkiness = 0.05 + 0.40 * Math.pow(r.nextDouble(), 1.5);
        // +5% RT over 3 h on task (Boksem et al. 2005).
        b.fatiguePerHour = 0.005 + 0.03 * r.nextDouble();
        b.practiceGain = 0.15 + 0.25 * r.nextDouble();
        // Schedule. Mid-sleep on free days: mode 04:14, near-normal (Roenneberg et al. 2007).
        b.sleepHours = clamp(7.6 + 0.8 * r.nextGaussian(), 5.5, 10);
        b.sleepStartHour = wrapHour(4.25 + 1.2 * r.nextGaussian() - b.sleepHours / 2);
        b.busyStartHour = clamp(8.3 + 0.8 * r.nextGaussian(), 6, 11);
        b.busyHours = clamp(8.5 + 0.8 * r.nextGaussian(), 6, 10.5);
        b.busyWeight = 0.05 + 0.30 * r.nextDouble() * r.nextDouble();
        // MMO population peaks ~19:00 and bottoms out ~04:00 (Pittman & GauthierDickey 2010).
        b.eveningPeakHour = clamp(19.5 + 1.2 * r.nextGaussian(), 16, 23.5);
        b.dailyMinutes = 150 * Math.exp(0.4 * r.nextGaussian());
        // Sittings: WoW Weibull(scale 69.75 min, shape 0.75), Warhammer Online (59.8, 0.83), same paper.
        b.sessionMinutes = 65 * Math.exp(0.35 * r.nextGaussian());
        b.sessionShape = 0.72 + 0.12 * r.nextDouble();
        b.skipDayProbability = 0.08 + 0.25 * r.nextDouble();
        return b.build();
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
