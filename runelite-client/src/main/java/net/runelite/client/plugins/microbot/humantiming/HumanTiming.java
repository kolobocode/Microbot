package net.runelite.client.plugins.microbot.humantiming;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Human-looking delays between actions, for one player.
 *
 * <p>Every delay is built from:
 * <ol>
 *   <li>A right-skewed body: shifted log-normal for self-paced gaps, ex-Gaussian for reactions.
 *       Mean &gt; median, no hard floor or ceiling. Nothing is ever clamped.</li>
 *   <li>Serial correlation: consecutive gaps of the same kind share a rhythm (AR(1) on the log scale),
 *       plus a slow tempo drift shared by all actions. Both keep the marginal exactly log-normal.</li>
 *   <li>A <b>load</b> that builds up while the player is active and drains during breaks. It drives
 *       fatigue (slightly slower), lapses (more long pauses) and attention (longer and more frequent
 *       distracted spells). A fresh or rested player starts focused.</li>
 *   <li>Practice: the first repetitions of a task are slower; a long break brings the warm-up back.</li>
 *   <li>A tail: ~0.5% of gaps become a 4-35 s pause, and decision points carry a further ~8% chance
 *       of an 8-13 s think.</li>
 * </ol>
 *
 * <h2>Working with other pauses</h2>
 * Activity is every {@link #delay} call plus every {@link #active()} heartbeat. Any time between
 * activities that our own delays didn't cover is an <b>outside pause</b>, whatever caused it: a
 * BreakHandler logout, a Microbot antiban cooldown or micro break, a manual pause, a human taking
 * over, a slow script step. The model humanises around it:
 * <ul>
 *   <li>3 s or more: the next action gets a resumption lag (longer after longer pauses).</li>
 *   <li>4-60 s: counts as one of our own long pauses, so combined pausing doesn't double.</li>
 *   <li>{@value #BREAK_THRESHOLD_MS} ms or more: a break. Load drains, attention resets to focused,
 *       warm-up partly returns.</li>
 * </ul>
 * Call {@code active()} from your script loop whenever the script really runs, and wrap long
 * blocking calls (web walks) in {@link #busy(Runnable)}, so watching your character walk is not
 * mistaken for a pause. A break you ran yourself can be reported with {@link #onBreak(long)},
 * including one shorter than the threshold.
 *
 * <p>Use one instance per player per script run. Not thread-safe; call it from the script thread.
 *
 * <pre>
 * HumanTiming timing = new HumanTiming(HumanProfile.fromName(Rs2Player.getLocalPlayer().getName()));
 * // in the loop, after super.run() passed:
 * timing.active();
 * timing.reactAfter(Rs2Bank::isOpen, 5000);     // wait for the bank, then react like a person
 * Rs2Bank.withdrawAll(logId);
 * timing.sleep(Action.ROUTINE);
 * </pre>
 */
public final class HumanTiming {
    /** Share of all gaps that turn into a long pause (looked away, phone, re-reading chat). */
    public static final double LONG_PAUSE_P = 0.005;
    /** Long pause length: log-normal, median 10 s, 95% between ~4 and ~27 s. */
    static final Shape LONG_PAUSE = Shape.lognormal(0, 10_000, 0.5);
    /** Typical extra chance of a separate 8-13 s think at a decision point (varies per profile). */
    public static final double DECISION_PAUSE_P = 0.08;
    /** Decision think length: log-normal, median 10.2 s, 95% between ~8 and ~13 s. */
    static final Shape DECISION_PAUSE = Shape.lognormal(0, 10_200, 0.12);
    /** Noticing an unexpected event while busy with something else (ex-Gaussian). */
    static final Shape NOTICE = Shape.exGaussian(330, 50, 150);
    /** Stop-signal reaction time: cancelling an action that is already planned (~200 ms, SD ~34; Logan and Cowan 1984, Congdon et al. 2012). */
    static final Shape STOP = Shape.lognormal(0, 200, 0.17);
    /** Re-planning after an interrupt, before the new action starts. */
    static final Shape REPLAN = Shape.lognormal(100, 600, 0.45);
    /**
     * Fitts' law mouse movement time: a + b * log2(distance / width + 1), with log-normal noise.
     * Gives 4-5 bits/s for typical moves, the ISO 9241-9 mouse range (Soukoreff and MacKenzie 2004).
     */
    static final double FITTS_A_MS = 100;
    static final double FITTS_B_MS = 165;
    static final double FITTS_NOISE = 0.2;
    /**
     * Share of the log-scale variance carried by a slow tempo drift shared by all actions. Human
     * response series stay positively correlated out to ~30 trials (Wagenmakers et al. 2004), which
     * a fast AR(1) alone can't produce.
     */
    static final double TEMPO_SHARE = 0.12;
    static final double TEMPO_RHO = 0.98;
    /**
     * Growth of the long-pause chance per hour of load. Time-on-task barely slows responses (+5% in
     * 3 h) but nearly doubles misses (11.5% to 19.7%) (Boksem et al. 2005): fatigue shows up as lapses.
     */
    static final double LAPSE_GROWTH_PER_HOUR = 0.25;

    /** No activity for this long counts as a break. Longer than the longest normal in-play pause. */
    public static final long BREAK_THRESHOLD_MS = 60_000;
    /**
     * Load drains exponentially during a break with this time constant: a 2 min break recovers ~18%,
     * 10 min ~63%, 20 min ~86%, an hour all of it. Brief breaks prevent the vigilance decrement
     * (Ariga and Lleras 2011); the time constant itself is an estimate.
     */
    static final long RECOVERY_TAU_MS = 10 * 60_000;
    /** Warm-up comes back with break length: after ~30 min most of it, after a couple of hours all of it. */
    static final long PRACTICE_TAU_MS = 30 * 60_000;
    /**
     * Distracted share of time as a function of load: afkiness * (BASE + SLOPE * loadHours). Just after
     * a break it is 0.6x the player's usual share, at one hour of load 1x, at 2.5 h 1.6x. Mind-wandering
     * rises with time on task (Thomson et al. 2014); the slope is an estimate.
     */
    static final double ATTENTION_BASE = 0.6;
    static final double ATTENTION_SLOPE_PER_HOUR = 0.4;
    static final double MAX_DISTRACTED_SHARE = 0.7;

    /**
     * Outside pauses (anything that stopped the script that wasn't one of our own delays) of at
     * least this long get a resumption lag before the next action: people need a moment to pick up
     * where they left off.
     */
    public static final long RESUME_MIN_GAP_MS = 3_000;
    /** Resumption lag after a short outside pause (3-60 s): re-attending, median ~0.6 s. */
    static final Shape RESUME_SHORT = Shape.lognormal(100, 600, 0.45);
    /**
     * Resumption lag after a break, median ~1.5 s at 1 min growing with the log of the break length
     * (x1.46 at 10 min, x1.8 at 1 h). Resumption lags grow with interruption length and level off
     * (Altmann and Trafton 2002; Monk, Trafton and Boehm-Davis 2008); the numbers are estimates.
     */
    static final Shape RESUME_BREAK = Shape.lognormal(300, 1500, 0.5);
    static final double RESUME_GROWTH_PER_LN_MINUTE = 0.2;
    /** An outside pause at least this long counts as one of our own long pauses (see {@link #LONG_PAUSE_P}). */
    static final long LONG_PAUSE_CREDIT_MIN_MS = 4_000;
    /** At most this many outside pauses are banked against future long pauses. */
    static final int MAX_PAUSE_CREDIT = 3;

    /** Overshoot limit; a safety bound set high enough that the model, not the limit, decides the count. */
    public static final int DEFAULT_MAX_OVERSHOOT = 5;
    /** Repeating a click on the same target still needs a small re-aim. */
    static final double REAIM_PX = 12;
    static final double REAIM_TARGET_PX = 30;
    /** Faster than this is an anticipation, not a reaction; such draws are redrawn. Practically never happens. */
    static final double MIN_REACTION_MS = 80;
    /** Distracted spells: median length in minutes (focused spells are sized from the distracted share). */
    static final double DISTRACTED_MEDIAN_MIN = 1.5;
    static final double DWELL_SIGMA = 0.8;
    /** Distracted players are this much more likely to drift off into a long pause. */
    static final double DISTRACTED_TAIL_FACTOR = 3;
    /** After this long without an action of a kind, its rhythm starts fresh. */
    static final long RHYTHM_RESET_MS = 60_000;
    /** Poll interval of {@link #reactAfter}: about one client frame, so the event is seen when a person would see it. */
    static final long FRAME_MS = 20;

    private final HumanProfile profile;
    private final Random rng;
    private final LongSupplier clock;
    private final Map<Action, Chain> chains = new EnumMap<>(Action.class);
    private double tempo;
    private boolean distracted;
    private long attentionUntilMs = Long.MIN_VALUE;
    /** Effective time on task, ms: grows while active, drains during breaks. */
    private double loadMs;
    /** End of the last known activity (may lie in the future while a delay is being slept). */
    private long lastActiveMs = Long.MIN_VALUE;
    private int breakCount;
    private long lastBreakMs;
    /** When the last break (any source) ended, or when this instance / session started. */
    private long lastBreakEndMs = Long.MIN_VALUE;
    /** Length of an outside pause the next action still has to resume from (0 = none). */
    private long pendingResumeMs;
    /** Outside long pauses banked against our own long-pause branch. */
    private int pauseCredit;
    private int busyDepth;

    /** Per-kind rhythm and practice state. */
    private static final class Chain {
        double z;
        long lastAtMs = Long.MIN_VALUE;
        double reps;
    }

    public HumanTiming(HumanProfile profile) {
        this(profile, new Random(), System::currentTimeMillis);
    }

    /** Explicit random source and clock, for tests and offline simulation. */
    public HumanTiming(HumanProfile profile, Random rng, LongSupplier clockMs) {
        this.profile = profile;
        this.rng = rng;
        this.clock = clockMs;
        for (Action a : Action.values()) chains.put(a, new Chain());
        this.tempo = rng.nextGaussian();
    }

    public HumanProfile profile() {
        return profile;
    }

    // ------------------------------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------------------------------

    /** Think time before the next input of this kind, in ms. Counts as activity. */
    public long delay(Action action) {
        long now = clock.getAsLong();
        touch(now);
        long result = Math.round(draw(action, now));
        account(now, result);
        return result;
    }

    /**
     * Click-to-click gap when the mouse moves instantly: think time plus a Fitts' law movement time.
     * Don't use this when Microbot's natural mouse is on; it already spends the travel time.
     */
    public long clickGap(Action action, double distancePx, double targetSizePx) {
        return delay(action) + movementMs(distancePx, targetSizePx);
    }

    /** Mouse travel time for a move of {@code distancePx} onto a target {@code targetSizePx} wide. */
    public long movementMs(double distancePx, double targetSizePx) {
        if (distancePx < 1) return 0;
        double bits = Math.log(distancePx / Math.max(1, targetSizePx) + 1) / Math.log(2);
        double ms = (FITTS_A_MS + FITTS_B_MS * bits) * profile.speed() * Math.exp(FITTS_NOISE * rng.nextGaussian());
        return Math.round(ms);
    }

    /**
     * Plans the reaction to an interrupt that hits while the player is repeating {@code pace}-type
     * actions. Call it the moment your script detects the interrupt; then do the overshoot repeats,
     * then the new action. See {@link InterruptPlan}.
     *
     * @param maxOvershoot most extra repeats allowed (0 if a repeat would do harm)
     */
    public InterruptPlan interrupt(Action pace, int maxOvershoot) {
        long now = clock.getAsLong();
        touch(now);
        updateAttention(now);
        double attention = distracted ? Action.REACT.distractedFactor : 1;
        double notice = reaction(NOTICE, profile.reactionScale() * attention)
                + STOP.sample(rng, rng.nextGaussian(), 1, 1);
        double respond = notice + REPLAN.sample(rng, rng.nextGaussian(), profile.speed() * fatigue(), profile.spread());

        // The interrupt lands part-way through the gap in progress; every action that starts before
        // the player has noticed and stopped still comes out. A repeat costs think time plus the
        // click itself (a small re-aim onto the same target).
        long[] gaps = new long[Math.max(0, maxOvershoot)];
        int n = 0;
        double last = 0;
        double t = rng.nextDouble() * repeatGap(pace, now);
        while (t < notice && n < gaps.length) {
            gaps[n++] = Math.round(t - last);
            last = t;
            t += repeatGap(pace, now);
        }
        long[] overshoot = new long[n];
        System.arraycopy(gaps, 0, overshoot, 0, n);
        InterruptPlan plan = new InterruptPlan(overshoot, Math.round(respond - last), Math.round(notice));
        account(now, plan.totalMs());
        return plan;
    }

    /** {@link #interrupt(Action, int)} with a limit of {@value #DEFAULT_MAX_OVERSHOOT}, which rarely binds. */
    public InterruptPlan interrupt(Action pace) {
        return interrupt(pace, DEFAULT_MAX_OVERSHOOT);
    }

    // ------------------------------------------------------------------------------------------
    // Sleeping (real time)
    // ------------------------------------------------------------------------------------------

    /** Sleeps a {@link #delay} of this kind. Returns false if the thread was interrupted. */
    public boolean sleep(Action action) {
        return pause(delay(action));
    }

    /**
     * Waits until {@code condition} is true, then reacts like a person would ({@link Action#REACT})
     * instead of acting on the same millisecond. Polls at about frame rate so the event is noticed
     * when it appears on screen, not up to a script-loop later. The wait counts as activity.
     *
     * @param timeoutMs safety limit on the wait for the condition; not part of the timing model
     * @return true if the condition became true (the reaction delay has then been slept)
     */
    public boolean reactAfter(BooleanSupplier condition, long timeoutMs) {
        long deadline = clock.getAsLong() + timeoutMs;
        while (!condition.getAsBoolean()) {
            active();
            if (clock.getAsLong() >= deadline) return false;
            if (!pause(FRAME_MS)) return false;
        }
        return pause(delay(Action.REACT));
    }

    /** Sleeps {@code ms}; returns false (and keeps the interrupt flag) if the thread was interrupted. */
    public static boolean pause(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Activity, breaks and sessions
    // ------------------------------------------------------------------------------------------

    /**
     * Heartbeat: the player is at it right now. Call it from the script loop whenever the script
     * really runs (logged in, not paused), including while walking or waiting. A gap of more than
     * {@value #BREAK_THRESHOLD_MS} ms between heartbeats is recorded as a break.
     */
    public void active() {
        touch(clock.getAsLong());
    }

    /**
     * Records a break of {@code breakMs} that just ended. Use it when you ran the break yourself and
     * know its length (also for breaks shorter than the detection threshold); detected breaks don't
     * need it.
     */
    public void onBreak(long breakMs) {
        long now = clock.getAsLong();
        recover(Math.max(0, breakMs), now);
        pendingResumeMs = Math.max(pendingResumeMs, breakMs);
        lastActiveMs = now;
    }

    /**
     * Runs a long blocking call (a web walk, a long dialogue) as activity: the player is watching it
     * happen, so it is neither a pause nor a break, and no resumption lag follows.
     */
    public void busy(Runnable work) {
        busyGet(() -> {
            work.run();
            return null;
        });
    }

    /** {@link #busy(Runnable)} for calls that return a value. */
    public <T> T busyGet(java.util.function.Supplier<T> work) {
        long start = clock.getAsLong();
        touch(start);
        busyDepth++;
        try {
            return work.get();
        } finally {
            busyDepth--;
            long end = clock.getAsLong();
            account(start, end - start);
        }
    }

    /** Time since the last break of any source ended (or since the session started), ms. */
    public long msSinceLastBreak() {
        long now = clock.getAsLong();
        return lastBreakEndMs == Long.MIN_VALUE ? 0 : Math.max(0, now - lastBreakEndMs);
    }

    /** Call when the player switches to a different task: the practice warm-up starts over. */
    public void newTask() {
        for (Chain c : chains.values()) c.reps = 0;
    }

    /** A completely fresh start (new day, new player at the keyboard): load, warm-up and break count reset. */
    public void newSession() {
        long now = clock.getAsLong();
        loadMs = 0;
        breakCount = 0;
        lastBreakMs = 0;
        lastActiveMs = now;
        lastBreakEndMs = now;
        pendingResumeMs = 0;
        pauseCredit = 0;
        distracted = false;
        attentionUntilMs = Long.MIN_VALUE;
        newTask();
    }

    /** Effective hours on task: grows while active, drains during breaks. */
    public double loadHours() {
        return loadMs / 3_600_000.0;
    }

    /** Breaks recorded since the last {@link #newSession()} (detected or reported). */
    public int breakCount() {
        return breakCount;
    }

    /** Length of the most recent break, ms (0 if none yet). */
    public long lastBreakMs() {
        return lastBreakMs;
    }

    /** True while the player is in a distracted spell (slower reactions, more long pauses). */
    public boolean isDistracted() {
        updateAttention(clock.getAsLong());
        return distracted;
    }

    /** Long-run share of time distracted at the current load. */
    public double distractedShare() {
        return profile.afkiness() <= 0 ? 0
                : Math.min(MAX_DISTRACTED_SHARE, profile.afkiness() * (ATTENTION_BASE + ATTENTION_SLOPE_PER_HOUR * loadHours()));
    }

    /** One-line state for overlays and logs. */
    public String describe() {
        return String.format(Locale.ROOT, "%s, load %.2f h, %d break%s", isDistracted() ? "distracted" : "focused",
                loadHours(), breakCount, breakCount == 1 ? "" : "s");
    }

    // ------------------------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------------------------

    /**
     * Records activity at {@code now}. Time since the last activity that none of our own delays
     * covered is an outside pause: a long one is a break, a medium one counts as a long pause taken,
     * and either way the next action gets a resumption lag.
     */
    private void touch(long now) {
        if (lastActiveMs == Long.MIN_VALUE) {
            lastActiveMs = now;
            lastBreakEndMs = now;
            return;
        }
        long gap = now - lastActiveMs;
        if (busyDepth > 0) {
            if (gap > 0) loadMs += gap;
        } else if (gap >= BREAK_THRESHOLD_MS) {
            recover(gap, now);
            pendingResumeMs = Math.max(pendingResumeMs, gap);
        } else if (gap > 0) {
            loadMs += gap;
            if (gap >= LONG_PAUSE_CREDIT_MIN_MS) pauseCredit = Math.min(MAX_PAUSE_CREDIT, pauseCredit + 1);
            if (gap >= RESUME_MIN_GAP_MS) pendingResumeMs = Math.max(pendingResumeMs, gap);
        }
        if (now > lastActiveMs) lastActiveMs = now;
    }

    /** The {@code ms} starting at {@code now} are spent playing (the delay being slept). */
    private void account(long now, long ms) {
        long end = now + ms;
        if (end > lastActiveMs) {
            loadMs += end - Math.max(now, lastActiveMs);
            lastActiveMs = end;
        }
    }

    private void recover(long breakMs, long now) {
        loadMs *= Math.exp(-breakMs / (double) RECOVERY_TAU_MS);
        double practiceKeep = Math.exp(-breakMs / (double) PRACTICE_TAU_MS);
        for (Chain c : chains.values()) c.reps *= practiceKeep;
        breakCount++;
        lastBreakMs = breakMs;
        lastBreakEndMs = now;
        // Back from a break: focused, with a fresh focused spell sized for the drained load.
        distracted = false;
        attentionUntilMs = profile.afkiness() <= 0 ? Long.MAX_VALUE : now + dwellMs(false);
    }

    /** One draw of think time for {@code action}, with rhythm, practice, load, attention and the tail. */
    private double draw(Action action, long now) {
        updateAttention(now);
        Chain chain = chains.get(action);
        Shape shape = profile.shape(action);
        double scale = fatigue() * practice(chain.reps) * (distracted ? action.distractedFactor : 1);

        tempo = TEMPO_RHO * tempo + Math.sqrt(1 - TEMPO_RHO * TEMPO_RHO) * rng.nextGaussian();
        double ms;
        if (shape.family() == Shape.Family.SHIFTED_LOGNORMAL) {
            double fresh = rng.nextGaussian();
            double rho = profile.autocorrelation();
            boolean newRhythm = chain.lastAtMs == Long.MIN_VALUE || now - chain.lastAtMs > RHYTHM_RESET_MS;
            chain.z = newRhythm ? fresh : rho * chain.z + Math.sqrt(1 - rho * rho) * fresh;
            // Two independent unit-variance parts, so z stays standard normal: the marginal is unchanged.
            double z = Math.sqrt(1 - TEMPO_SHARE) * chain.z + Math.sqrt(TEMPO_SHARE) * tempo;
            ms = shape.sample(rng, z, scale * profile.speed(), profile.spread());
        } else {
            ms = reaction(shape, scale * profile.reactionScale());
        }

        double tailP = LONG_PAUSE_P * profile.tailScale() * (distracted ? DISTRACTED_TAIL_FACTOR : 1)
                * (1 + LAPSE_GROWTH_PER_HOUR * loadHours());
        if (rng.nextDouble() < tailP) {
            if (pauseCredit > 0) {
                pauseCredit--; // something else already paused us recently; don't pause twice
            } else {
                ms += LONG_PAUSE.sample(rng, rng.nextGaussian(), 1, 1);
            }
        }
        if (pendingResumeMs > 0) {
            ms += resumeLag(pendingResumeMs);
            pendingResumeMs = 0;
        }
        if (action == Action.DECISION && rng.nextDouble() < profile.decisionPauseP()) {
            ms += DECISION_PAUSE.sample(rng, rng.nextGaussian(), 1, 1);
        }
        chain.reps++;
        chain.lastAtMs = now + Math.round(ms);
        return ms;
    }

    /** Time to pick up the task again after an outside pause of {@code pauseMs}. */
    private double resumeLag(long pauseMs) {
        if (pauseMs < BREAK_THRESHOLD_MS) {
            return RESUME_SHORT.sample(rng, rng.nextGaussian(), profile.reactionScale(), profile.spread());
        }
        double growth = 1 + RESUME_GROWTH_PER_LN_MINUTE * Math.log(pauseMs / 60_000.0);
        return RESUME_BREAK.sample(rng, rng.nextGaussian(), growth * profile.reactionScale(), profile.spread());
    }

    private double repeatGap(Action pace, long now) {
        return draw(pace, now) + movementMs(REAIM_PX, REAIM_TARGET_PX);
    }

    private double fatigue() {
        return 1 + profile.fatiguePerHour() * loadHours();
    }

    /** Power law of practice: 1 + gain / sqrt(1 + n). First repetition is {@code gain} slower. */
    private double practice(double reps) {
        return 1 + profile.practiceGain() / Math.sqrt(1 + reps);
    }

    private double reaction(Shape shape, double scale) {
        double ms;
        do {
            ms = shape.sample(rng, rng.nextGaussian(), scale, profile.spread());
        } while (ms < MIN_REACTION_MS);
        return ms;
    }

    /**
     * Alternating focused/distracted spells with log-normal lengths. Each spell is sized from the
     * distracted share at its start, so spells drift towards distraction as load builds up.
     */
    private void updateAttention(long now) {
        if (profile.afkiness() <= 0) {
            distracted = false;
            return;
        }
        if (attentionUntilMs == Long.MIN_VALUE) {
            distracted = false; // sitting down fresh
            attentionUntilMs = now + dwellMs(false);
            return;
        }
        while (now >= attentionUntilMs) {
            distracted = !distracted;
            attentionUntilMs += dwellMs(distracted);
        }
    }

    private long dwellMs(boolean nowDistracted) {
        double share = Math.max(0.01, distractedShare());
        double medianMin = nowDistracted ? DISTRACTED_MEDIAN_MIN : DISTRACTED_MEDIAN_MIN * (1 - share) / share;
        return Math.max(1, Math.round(medianMin * 60_000 * Math.exp(DWELL_SIGMA * rng.nextGaussian())));
    }
}
