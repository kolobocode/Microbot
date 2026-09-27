package net.runelite.client.plugins.microbot.humantiming;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * When a person plays: a daily rhythm (asleep at night, less during weekday work hours, most in the
 * evening), a few sittings per day of Weibull length, days off, and breaks within a sitting.
 * Human activity comes in bursts separated by long silences, not at a flat rate.
 *
 * <p>{@link #planDay} is deterministic per profile and date, so asking again (after a client restart)
 * gives the same plan.
 */
public final class SessionPlanner {
    /** Minimum gap between two sittings on the same day. */
    static final int MIN_GAP_MINUTES = 20;
    /** A sitting is only placed where the average activity weight is at least this. */
    static final double MIN_SESSION_WEIGHT = 0.15;

    private final HumanProfile profile;
    private final Random rng;

    public SessionPlanner(HumanProfile profile) {
        this(profile, new Random());
    }

    public SessionPlanner(HumanProfile profile, Random rng) {
        this.profile = profile;
        this.rng = rng;
    }

    /** One sitting, [start, end). */
    public static final class Session {
        private final LocalDateTime start;
        private final LocalDateTime end;

        Session(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        public LocalDateTime start() { return start; }
        public LocalDateTime end() { return end; }
        public Duration length() { return Duration.between(start, end); }

        public boolean contains(LocalDateTime t) {
            return !t.isBefore(start) && t.isBefore(end);
        }

        @Override
        public String toString() {
            return start + " -> " + end + " (" + length().toMinutes() + " min)";
        }
    }

    /** A break inside a sitting: play {@code workMs}, then step away for {@code breakMs}. */
    public static final class Break {
        public final long workMs;
        public final long breakMs;

        Break(long workMs, long breakMs) {
            this.workMs = workMs;
            this.breakMs = breakMs;
        }

        @Override
        public String toString() {
            return "Break{after " + workMs / 60_000 + " min, for " + breakMs / 1000 + " s}";
        }
    }

    /**
     * How likely this person is to be playing at {@code t}, 0..1: awake, not at work/school, with a
     * peak in the evening.
     */
    public double activityWeight(LocalDateTime t) {
        double h = t.getHour() + t.getMinute() / 60.0;
        double awake = 1 - window(h, profile.sleepStartHour(), profile.sleepHours(), 0.5);
        DayOfWeek d = t.getDayOfWeek();
        boolean weekday = d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY;
        double busy = weekday ? window(h, profile.busyStartHour(), profile.busyHours(), 0.5) : 0;
        double fromPeak = circularDistance(h, profile.eveningPeakHour());
        double evening = 0.35 + 0.65 * Math.exp(-0.5 * Math.pow(fromPeak / 2.5, 2));
        return awake * evening * (1 - busy * (1 - profile.busyWeight()));
    }

    /** The sittings planned for {@code date} (a sitting may run past midnight). Empty on a day off. */
    public List<Session> planDay(LocalDate date) {
        // java.util.Random's first draws are correlated for neighbouring seeds; mix the day in properly.
        Random r = new Random(splitMix64(profile.seed() ^ splitMix64(date.toEpochDay())));
        List<Session> out = new ArrayList<>();
        if (r.nextDouble() < profile.skipDayProbability()) return out;

        DayOfWeek dow = date.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        double targetMinutes = profile.dailyMinutes() * (weekend ? 1.35 : 1) * Math.exp(0.5 * r.nextGaussian());

        // Start times are drawn from the activity curve in 5-minute slots.
        LocalDateTime midnight = date.atStartOfDay();
        double[] cdf = new double[288];
        double sum = 0;
        for (int i = 0; i < cdf.length; i++) {
            sum += activityWeight(midnight.plusMinutes(5L * i));
            cdf[i] = sum;
        }

        double planned = 0;
        for (int tries = 0; tries < 12 && planned < targetMinutes; tries++) {
            int slot = sampleSlot(cdf, r.nextDouble() * sum);
            LocalDateTime start = midnight.plusMinutes(5L * slot + r.nextInt(5));
            // Weibull sitting length (Pittman & GauthierDickey 2010): many short logins, a long tail.
            double weibull = profile.sessionMinutes() * Math.pow(-Math.log(1 - r.nextDouble()), 1 / profile.sessionShape());
            long minutes = Math.max(1, Math.round(weibull));
            Session s = new Session(start, start.plusMinutes(minutes));
            if (meanWeight(s) < MIN_SESSION_WEIGHT || overlaps(out, s)) continue;
            out.add(s);
            planned += minutes;
        }
        out.sort((a, b) -> a.start.compareTo(b.start));
        return Collections.unmodifiableList(out);
    }

    /** The sitting that covers {@code now}, if any (including one that started yesterday evening). */
    public Optional<Session> currentSession(LocalDateTime now) {
        for (LocalDate d : new LocalDate[]{now.toLocalDate(), now.toLocalDate().minusDays(1)}) {
            for (Session s : planDay(d)) {
                if (s.contains(now)) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** The next sitting that starts after {@code now}, looking up to two weeks ahead. */
    public Optional<Session> nextSession(LocalDateTime now) {
        for (int i = 0; i < 14; i++) {
            for (Session s : planDay(now.toLocalDate().plusDays(i))) {
                if (s.start.isAfter(now)) return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * The next break inside a sitting. Work spells are log-normal (median ~22 min); breaks are a
     * heavy-tailed mix of short ones (median ~75 s: drink, toilet) and longer ones (median ~8 min).
     */
    public Break nextBreak() {
        return nextBreak(true);
    }

    /**
     * Like {@link #nextBreak()}; with {@code allowLong = false} only the short kind (for breaks taken
     * while staying logged in, leaving long ones to a logout break handler).
     */
    public Break nextBreak(boolean allowLong) {
        double work = 22 * 60_000 * Math.exp(0.6 * rng.nextGaussian());
        double pause = !allowLong || rng.nextDouble() < 0.85
                ? 75_000 * Math.exp(0.8 * rng.nextGaussian())
                : 8 * 60_000 * Math.exp(0.6 * rng.nextGaussian());
        return new Break(Math.round(work), Math.round(pause));
    }

    private double meanWeight(Session s) {
        double sum = 0;
        int n = 0;
        for (LocalDateTime t = s.start; t.isBefore(s.end); t = t.plusMinutes(10)) {
            sum += activityWeight(t);
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private static boolean overlaps(List<Session> sessions, Session s) {
        for (Session o : sessions) {
            if (s.start.isBefore(o.end.plusMinutes(MIN_GAP_MINUTES)) && o.start.isBefore(s.end.plusMinutes(MIN_GAP_MINUTES))) {
                return true;
            }
        }
        return false;
    }

    private static int sampleSlot(double[] cdf, double u) {
        int lo = 0;
        int hi = cdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Smooth 0..1 membership of the circular window [start, start + length) hours, edges ~{@code edge} h wide. */
    static double window(double hour, double start, double length, double edge) {
        double d = ((hour - start) % 24 + 24) % 24;
        if (d > 12 + length / 2) d -= 24; // just before the window: on its rising edge
        return logistic(d / edge) * logistic((length - d) / edge);
    }

    static long splitMix64(long x) {
        long z = x + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double logistic(double x) {
        return 1 / (1 + Math.exp(-4 * x));
    }

    private static double circularDistance(double a, double b) {
        double d = Math.abs(a - b) % 24;
        return Math.min(d, 24 - d);
    }
}
