package net.runelite.client.plugins.microbot.util.human;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.microbot.humantiming.HumanProfile;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * LOCAL PATCH (not upstream): the thresholds a script hands the shared helpers ("eat at 50%", "drink at 40
 * prayer") become a person's habit instead of a fixed line.
 * <p>
 * Each time the player would act (one bite, one dose) the actual threshold is drawn once from a bell curve
 * around the script's value and held until the item is used, so checking it every loop doesn't turn the
 * spread back into "first high roll wins". Limits never clamp a draw: the whole curve is shifted inside the
 * limits, and narrowed when it doesn't fit, so there is no pile-up at a bound ({@link #bell}).
 * <p>
 * The player's own lean (eats early or late, tight or loose) comes from the same HumanId as HumanTiming and
 * HumanMouse. Every draw and every use is written to {@code ~/.runelite/microbot/human-policy.tsv}, so the
 * curves can be checked afterwards.
 * <p>
 * {@code -Dmicrobot.humanPolicy=false} at client start turns it off; it is also off with HumanInput.
 */
@Slf4j
public final class HumanPolicy {
    public static final boolean ENABLED = HumanInput.ENABLED
            && !"false".equalsIgnoreCase(System.getProperty("microbot.humanPolicy"));

    /** Half width of every curve, in standard deviations. Density at the edge is 4% of the peak. */
    static final double K = 2.5;

    /** Spread of the eat point around the script's value, in % of max HP, for an average player. */
    static final double EAT_SD = 6;
    /** Spread of the prayer point around the script's value, as a share of max prayer. */
    static final double PRAY_SD_SHARE = 0.05;
    static final double PRAY_SD_MIN = 1.5;

    /** A held draw nobody asked for this long is from an old fight: draw again. */
    static final long EPISODE_IDLE_MS = 90_000;

    private static final Map<String, Episode> episodes = new ConcurrentHashMap<>();
    private static volatile Traits traits;
    private static volatile long traitsSeed = Long.MIN_VALUE;

    private HumanPolicy() {
    }

    // ------------------------------------------------------------------------------------------
    // The curve
    // ------------------------------------------------------------------------------------------

    /**
     * One draw from a normal curve that lies whole inside [lo, hi]. The centre moves inward until both
     * tails (K sd) fit; if the range is narrower than the curve, the curve is narrowed first. Draws are
     * resampled inside the tails, never clamped.
     */
    static double bell(double center, double sd, double lo, double hi, Random rng) {
        if (!(hi > lo)) return (lo + hi) / 2;
        double s = Math.max(1e-6, Math.min(sd, (hi - lo) / (2 * K)));
        double c = Math.max(lo + K * s, Math.min(hi - K * s, center));
        double z;
        do {
            z = rng.nextGaussian();
        } while (Math.abs(z) > K);
        return c + z * s;
    }

    // ------------------------------------------------------------------------------------------
    // Helper thresholds (Rs2Player.eatAt, drinkPrayerPotionAt, drinkPrayerPotion)
    // ------------------------------------------------------------------------------------------

    /** The HP % to eat at this time, for a script that asked for {@code scriptPercent}. */
    public static double eatPercent(int scriptPercent) {
        if (!ENABLED) return scriptPercent;
        Traits t = traits();
        double lo = scriptPercent * 0.6;
        double hi = Math.min(95, scriptPercent * 1.4 + 5);
        return held("eat|" + scriptPercent, scriptPercent, 100,
                () -> bell(scriptPercent + t.eatBias, EAT_SD * t.spread, lo, hi, rng()));
    }

    /** The prayer points to drink at this time, for a script that asked for {@code scriptPoints}. */
    public static int prayerPoints(int scriptPoints, int maxPrayer) {
        if (!ENABLED || maxPrayer <= 0) return scriptPoints;
        Traits t = traits();
        double sd = Math.max(PRAY_SD_MIN, PRAY_SD_SHARE * maxPrayer) * t.spread;
        double lo = Math.max(1, scriptPoints * 0.5);
        double hi = Math.min(maxPrayer - 1, scriptPoints * 1.3 + 2);
        return (int) Math.round(held("pray|" + scriptPoints, scriptPoints, maxPrayer,
                () -> bell(scriptPoints + t.prayBias * maxPrayer, sd, lo, hi, rng())));
    }

    /**
     * For {@code drinkPrayerPotion()}: drink so that a whole dose fits ({@code fullDoseAt} = max prayer minus
     * one dose). The curve sits below that line, never over it.
     */
    public static int prayerPotionPoints(int fullDoseAt, int maxPrayer) {
        // Off: upstream's own roll (a fresh one every call).
        if (!ENABLED || maxPrayer <= 0) return Math.min(fullDoseAt, (int) Math.round(fullDoseAt - 5 + rng().nextGaussian() * 3));
        Traits t = traits();
        double sd = Math.max(PRAY_SD_MIN, PRAY_SD_SHARE * maxPrayer) * t.spread;
        double lo = Math.max(1, fullDoseAt - 0.35 * maxPrayer);
        return (int) Math.round(held("praypot|" + fullDoseAt, fullDoseAt, maxPrayer,
                () -> bell(fullDoseAt - 5 + t.prayBias * maxPrayer, sd, lo, fullDoseAt, rng())));
    }

    /** A food / prayer item was used: the next time gets a new draw. */
    public static void consumed(String kind) {
        episodes.keySet().removeIf(k -> k.startsWith(kind));
    }

    // ------------------------------------------------------------------------------------------
    // Held draws
    // ------------------------------------------------------------------------------------------

    static final class Episode {
        final double value;
        volatile long seenMs;

        Episode(double value, long now) {
            this.value = value;
            this.seenMs = now;
        }
    }

    /** The draw for {@code key}, made on first use and held until {@link #consumed} or idle. */
    static double held(String key, double scriptValue, double max, DoubleSupplier draw) {
        long now = System.currentTimeMillis();
        Episode e = episodes.get(key);
        if (e == null || now - e.seenMs > EPISODE_IDLE_MS) {
            e = new Episode(draw.getAsDouble(), now);
            episodes.put(key, e);
            record(key, "draw", scriptValue, e.value, Double.NaN, max);
        }
        e.seenMs = now;
        return e.value;
    }

    static void clearForTest() {
        episodes.clear();
        traits = null;
        traitsSeed = Long.MIN_VALUE;
    }

    // ------------------------------------------------------------------------------------------
    // The player's own lean
    // ------------------------------------------------------------------------------------------

    /** Per-player habits, fixed for a HumanId. */
    static final class Traits {
        /** % of max HP added to where the script eats; positive = eats early. */
        final double eatBias;
        /** Share of max prayer added to where the script drinks. */
        final double prayBias;
        /** Multiplier on every spread; some players are steadier than others. */
        final double spread;
        /** How long this player lets a script's own "eat now" slide in a fight, in % of max HP. */
        final double eatSlack;
        final double praySlackShare;

        Traits(long seed) {
            Random r = new Random(seed ^ 0x6A09E667F3BCC909L);
            eatBias = r.nextGaussian() * 4;
            prayBias = r.nextGaussian() * 0.03;
            spread = Math.exp(r.nextGaussian() * 0.2);
            eatSlack = 10 + r.nextDouble() * 8;
            praySlackShare = 0.08 + r.nextDouble() * 0.08;
        }
    }

    static Traits traits() {
        HumanProfile p = HumanInput.timingProfile();
        long seed = p == null ? 0 : p.seed();
        Traits t = traits;
        if (t == null || seed != traitsSeed) {
            t = new Traits(seed);
            traits = t;
            traitsSeed = seed;
        }
        return t;
    }

    static Random rng() {
        return ThreadLocalRandom.current();
    }

    // ------------------------------------------------------------------------------------------
    // Decision log
    // ------------------------------------------------------------------------------------------

    private static final File LOG_FILE = new File(new File(RuneLite.RUNELITE_DIR, "microbot"), "human-policy.tsv");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile boolean logBroken;
    /** Tests switch the file off. */
    static volatile boolean logEnabled = true;

    /**
     * One line per draw and per use: time, key, event, the script's value, the drawn value, the value the
     * player was at, and the maximum (100 for HP %, max prayer for points).
     */
    static synchronized void record(String key, String event, double scriptValue, double drawn, double actual, double max) {
        if (logBroken || !logEnabled) return;
        try {
            boolean fresh = !LOG_FILE.exists();
            if (fresh) LOG_FILE.getParentFile().mkdirs();
            try (Writer w = new FileWriter(LOG_FILE, true)) {
                if (fresh) w.write("time\tkey\tevent\tscript\tdrawn\tactual\tmax\tthread\n");
                w.write(String.format(Locale.ROOT, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s%n",
                        LocalDateTime.now().format(TS), key, event, num(scriptValue), num(drawn), num(actual), num(max),
                        Thread.currentThread().getName()));
            }
        } catch (IOException | SecurityException ex) {
            logBroken = true;
            log.warn("[HumanPolicy] decision log off: {}", ex.toString());
        }
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.1f", v);
    }
}
