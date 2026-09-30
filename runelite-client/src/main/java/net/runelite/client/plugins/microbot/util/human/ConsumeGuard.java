package net.runelite.client.plugins.microbot.util.human;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.WorldType;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.player.Rs2Pvp;

import java.util.Locale;
import java.util.Random;

/**
 * LOCAL PATCH (not upstream): every "Eat" and "Drink" click of every script, also scripts with their own
 * hard-coded {@code if (hp < 50) eat}, passes here before the mouse moves. Two things:
 * <ul>
 *   <li><b>The game's own timer.</b> A second bite (or dose) before the 3-tick delay is over does nothing in
 *       game, so the click is dropped instead of spammed. The gap a player leaves is 1.8 s plus a human
 *       lag, not the script's loop time. Karambwan has its own timer, as in game.</li>
 *   <li><b>A script's own threshold, in a fight.</b> A player doesn't eat at the same HP every time: they
 *       notice a bit later on one bite than the next. For a click the script decided itself, a target
 *       below the script's HP is drawn once per bite from a bell curve that lies whole between a safety
 *       floor and the HP the script clicked at ({@link HumanPolicy#bell}), and the click is dropped until
 *       HP is at or under it, or a drawn wait ran out. The script asks again on its next loop, as it does
 *       for a click that didn't land. Same for prayer potions. Not in the wilderness or a PvP world, and
 *       not out of a fight.</li>
 * </ul>
 * A click the shared helpers ({@code Rs2Player.eatAt}, {@code drinkPrayerPotion...}) already chose the
 * moment for skips the second part.
 */
@Slf4j
public final class ConsumeGuard {
    /** Three game ticks: the eat and potion delay. */
    static final long GAME_DELAY_MS = 1_800;
    /** HP % a deferred bite never waits under. The curve is kept above it, not cut at it. */
    static final double EAT_FLOOR = 25;
    /** Prayer points (share of max) a deferred dose never waits under. */
    static final double PRAY_FLOOR_SHARE = 0.12;

    enum Kind {
        FOOD(0), COMBO_FOOD(1), PRAYER(2), POTION(2);

        /** Which game timer the item waits on: food, karambwan, or potions (prayer potions are potions). */
        final int timer;

        Kind(int timer) {
            this.timer = timer;
        }
    }

    /** When a shared helper on this thread chose the moment; expires, so it can't carry over to a later click. */
    private static final ThreadLocal<Long> DECIDED_AT = ThreadLocal.withInitial(() -> 0L);
    private static final long DECIDED_VALID_MS = 3_000;

    private static final long[] lastUse = new long[3];
    private static final long[] gapMs = new long[3];
    private static final boolean[] skipLogged = new boolean[3];
    private static volatile Defer eatDefer;
    private static volatile Defer prayDefer;

    private ConsumeGuard() {
    }

    /** The shared helper on this thread chose when to eat or drink; the next such click only obeys the timer. */
    public static void markDecided() {
        DECIDED_AT.set(System.currentTimeMillis());
    }

    /**
     * @param scriptThread false for a click sent from the client thread, which must not read game state here
     * @return false to drop the click
     */
    public static boolean allow(NewMenuEntry entry, boolean scriptThread) {
        boolean decided = System.currentTimeMillis() - DECIDED_AT.get() <= DECIDED_VALID_MS;
        DECIDED_AT.set(0L);
        if (!HumanPolicy.ENABLED || entry == null) return true;
        Kind kind = kindOf(entry.getOption(), entry.getTarget());
        if (kind == null) return true;
        try {
            long now = System.currentTimeMillis();
            synchronized (lastUse) {
                int i = kind.timer;
                if (now - lastUse[i] < gapMs[i]) {
                    // Once per bite: a script looping on it would fill the log.
                    if (!skipLogged[i]) HumanPolicy.record(key(kind), "skip-timer", Double.NaN, gapMs[i], now - lastUse[i], Double.NaN);
                    skipLogged[i] = true;
                    return false;
                }
            }
            if (!decided && scriptThread && !deferOk(kind)) return false;
            synchronized (lastUse) {
                int i = kind.timer;
                lastUse[i] = now;
                gapMs[i] = GAME_DELAY_MS + humanLag(HumanPolicy.rng());
                skipLogged[i] = false;
            }
            if (kind == Kind.FOOD || kind == Kind.COMBO_FOOD) {
                eatDefer = null;
                HumanPolicy.consumed("eat");
            } else if (kind == Kind.PRAYER) {
                prayDefer = null;
                HumanPolicy.consumed("pray");
            }
            return true;
        } catch (Exception ex) {
            log.debug("ConsumeGuard skipped", ex);
            return true;
        }
    }

    // ------------------------------------------------------------------------------------------
    // A script's own threshold
    // ------------------------------------------------------------------------------------------

    /** One bite or dose the script wants now: the level to wait for, and how long at most. */
    static final class Defer {
        final double target;
        final long untilMs;

        Defer(double target, long untilMs) {
            this.target = target;
            this.untilMs = untilMs;
        }
    }

    /** True when the click may go now; false while the drawn moment hasn't come yet. */
    private static boolean deferOk(Kind kind) {
        if (kind != Kind.FOOD && kind != Kind.PRAYER) return true;
        if (!Rs2Player.isInCombat() || Rs2Pvp.isInWilderness()
                || Microbot.getClient().getWorldType().contains(WorldType.PVP)) return true;
        HumanPolicy.Traits t = HumanPolicy.traits();
        long now = System.currentTimeMillis();
        if (kind == Kind.FOOD) {
            double hp = Rs2Player.getHealthPercentage();
            if (Double.isNaN(hp) || hp <= EAT_FLOOR) return true;
            Defer d = eatDefer;
            if (d == null || now > d.untilMs + HumanPolicy.EPISODE_IDLE_MS) {
                double lo = Math.max(EAT_FLOOR, hp - t.eatSlack * t.spread);
                d = new Defer(HumanPolicy.bell(hp, HumanPolicy.EAT_SD * t.spread, lo, hp, HumanPolicy.rng()),
                        now + waitMs(3_500, HumanPolicy.rng()));
                eatDefer = d;
                HumanPolicy.record("eat|script", "draw", hp, d.target, Double.NaN, 100);
            }
            return settle("eat|script", d, hp, 100, now);
        }
        int max = Rs2Player.getRealSkillLevel(Skill.PRAYER);
        int points = Rs2Player.getBoostedSkillLevel(Skill.PRAYER);
        double floor = Math.max(3, PRAY_FLOOR_SHARE * max);
        if (max <= 0 || points <= floor) return true;
        Defer d = prayDefer;
        if (d == null || now > d.untilMs + HumanPolicy.EPISODE_IDLE_MS) {
            double lo = Math.max(floor, points - t.praySlackShare * max * t.spread);
            double sd = Math.max(HumanPolicy.PRAY_SD_MIN, HumanPolicy.PRAY_SD_SHARE * max) * t.spread;
            d = new Defer(HumanPolicy.bell(points, sd, lo, points, HumanPolicy.rng()),
                    now + waitMs(14_000, HumanPolicy.rng()));
            prayDefer = d;
            HumanPolicy.record("pray|script", "draw", points, d.target, Double.NaN, max);
        }
        return settle("pray|script", d, points, max, now);
    }

    private static boolean settle(String key, Defer d, double level, double max, long now) {
        if (level <= d.target) {
            HumanPolicy.record(key, "use", Double.NaN, d.target, level, max);
            return true;
        }
        if (now >= d.untilMs) {
            HumanPolicy.record(key, "use-waited", Double.NaN, d.target, level, max);
            return true;
        }
        return false;
    }

    /** Longest a player lets it slide: right-skewed around {@code medianMs}. */
    static long waitMs(double medianMs, Random rng) {
        return Math.round(medianMs * Math.exp(rng.nextGaussian() * 0.45));
    }

    /** What a player adds to the game's delay before the next bite: right-skewed, median about 300 ms. */
    static long humanLag(Random rng) {
        return Math.round(300 * Math.exp(rng.nextGaussian() * 0.5));
    }

    // ------------------------------------------------------------------------------------------
    // Which click is what
    // ------------------------------------------------------------------------------------------

    static Kind kindOf(String option, String target) {
        if (option == null) return null;
        String o = option.replaceAll("<[^>]*>", "").trim().toLowerCase(Locale.ROOT);
        String t = target == null ? "" : target.replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
        if (o.equals("eat")) return t.contains("karambwan") ? Kind.COMBO_FOOD : Kind.FOOD;
        if (!o.equals("drink")) return null;
        if (t.contains("jug of wine")) return Kind.FOOD;
        if (t.contains("prayer") || t.contains("super restore") || t.contains("sanfew")
                || t.contains("moonlight")) return Kind.PRAYER;
        return Kind.POTION;
    }

    private static String key(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    static void clearForTest() {
        synchronized (lastUse) {
            java.util.Arrays.fill(lastUse, 0);
            java.util.Arrays.fill(gapMs, 0);
            java.util.Arrays.fill(skipLogged, false);
        }
        eatDefer = null;
        prayDefer = null;
        DECIDED_AT.set(0L);
    }
}
