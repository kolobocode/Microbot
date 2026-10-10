package net.runelite.client.plugins.microbot.util.walker;

import net.runelite.api.coords.WorldPoint;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/**
 * LOCAL PATCH: stops a script from sending the same walk again and again when it isn't getting any
 * closer. A shut door in front of a "Walk here" (Romeo &amp; Juliet's house, 2026-10-03) made the quest
 * script click the same spot every loop until the user switched it off.
 * <p>
 * One guard watches one lane of walks (the script's own walk clicks, or whole {@code walkTo} calls)
 * and judges each walk to the same target by evidence: did the player get closer than before? A walk
 * that didn't is a miss. After a few misses in a row it backs off (no clicks for a while), and after
 * the last back-off it gives up: it reports {@link Decision#STUCK} once and then holds without
 * clicking until the player has moved, the script wants somewhere else, or the blocker is gone.
 * <p>
 * Pure logic with an injected clock and jitter, so the decision table can be tested without a client.
 */
final class WalkStuckGuard {
    enum Decision {
        /** Send the walk. */
        GO,
        /** The last walk to this target is still under way; don't send it again, it counts as sent. */
        IN_FLIGHT,
        /** Backing off or given up: send nothing, the walk failed. */
        HOLD,
        /** Gave up just now. Send nothing; the caller tells the user, once. */
        STUCK
    }

    /** A walk target within this many tiles of the tracked one is the same target. */
    private static final int SAME_TARGET_TILES = 2;
    /** A player this far from where the guard gave up has been moved, by someone, so try again. */
    private static final int MOVED_AWAY_TILES = 2;
    /** No walk to this target for this long: forget the old misses. */
    private static final long STALE_MS = 5 * 60_000L;
    /** While given up, ask "is the blocker gone?" at most this often. */
    private static final long BLOCKER_PROBE_MS = 5_000L;

    private final String lane;
    private final long settleMs;
    private final long maxInFlightMs;
    private final int[] missesPerStage;
    private final long[] backoffMs;
    private final DoubleSupplier jitter;

    private WorldPoint target;
    private int targetPlane;
    private int bestDistance;
    private int distanceAtAttempt;
    private boolean attemptPending;
    private long lastAttemptAtMs;
    private int misses;
    private int stage;
    private long holdUntilMs;
    private boolean givenUp;
    private WorldPoint givenUpAt;
    private boolean blockedWhenGivenUp;
    private long lastBlockerProbeAtMs;

    /**
     * @param settleMs       after a walk is sent, how long before it can be judged (the player needs a
     *                       tick or two to start moving); calls in between are {@link Decision#IN_FLIGHT}
     * @param maxInFlightMs  a moving player keeps the last walk in flight for at most this long
     * @param missesPerStage misses in a row before each back-off; one entry per stage
     * @param backoffMs      the pause after each stage; after the last one the guard gives up
     * @param jitter         a factor around 1 for each pause, so the pauses aren't a fixed rhythm
     */
    WalkStuckGuard(String lane, long settleMs, long maxInFlightMs, int[] missesPerStage, long[] backoffMs,
                   DoubleSupplier jitter) {
        if (missesPerStage.length != backoffMs.length + 1) {
            throw new IllegalArgumentException("one more miss count than back-offs (the last one gives up)");
        }
        this.lane = lane;
        this.settleMs = settleMs;
        this.maxInFlightMs = maxInFlightMs;
        this.missesPerStage = missesPerStage.clone();
        this.backoffMs = backoffMs.clone();
        this.jitter = jitter;
    }

    /** Walk clicks a script sends itself: judged after ~3 ticks; 3 misses, 4 s, 2, 12 s, 2, 30 s, 2, give up. */
    static WalkStuckGuard forClicks() {
        return new WalkStuckGuard("click", 1_800L, 10_000L,
                new int[] {3, 2, 2, 2}, new long[] {4_000L, 12_000L, 30_000L}, WalkStuckGuard::defaultJitter);
    }

    /** Whole walkTo calls: each one already tried hard, so 2 misses, 10 s, 2, 30 s, 1, give up. */
    static WalkStuckGuard forWalks() {
        return new WalkStuckGuard("walk", 0L, 3_000L,
                new int[] {2, 2, 1}, new long[] {10_000L, 30_000L}, WalkStuckGuard::defaultJitter);
    }

    private static double defaultJitter() {
        return 0.7 + ThreadLocalRandom.current().nextDouble() * 0.6;
    }

    String lane() {
        return lane;
    }

    /**
     * Decides whether the walk to {@code walkTarget} may be sent now. Call before every walk.
     *
     * @param blockerGone optional cheap check whether nothing blocks the way to the target. Asked when
     *                    giving up and then at most every 5 s: when it said "blocked" at give-up and says
     *                    "clear" later (someone opened the door), the guard tries again. {@code null} when
     *                    the caller can't tell
     */
    synchronized Decision before(WorldPoint walkTarget, WorldPoint player, boolean playerMoving, long nowMs,
                                 BooleanSupplier blockerGone) {
        if (walkTarget == null || player == null) {
            return Decision.GO;
        }
        if (!isSameTarget(walkTarget) || player.getPlane() != targetPlane
                || (!givenUp && lastAttemptAtMs > 0 && nowMs - lastAttemptAtMs > STALE_MS)) {
            track(walkTarget, player);
            return Decision.GO;
        }

        int distance = distance(player, walkTarget);
        if (distance < bestDistance) {
            bestDistance = distance;
            misses = 0;
            stage = 0;
            holdUntilMs = 0;
        }

        if (givenUp) {
            if (givenUpAt != null && player.distanceTo2D(givenUpAt) >= MOVED_AWAY_TILES) {
                track(walkTarget, player);
                return Decision.GO;
            }
            if (blockedWhenGivenUp && blockerGone != null && nowMs - lastBlockerProbeAtMs >= BLOCKER_PROBE_MS) {
                lastBlockerProbeAtMs = nowMs;
                if (blockerGone.getAsBoolean()) {
                    track(walkTarget, player);
                    return Decision.GO;
                }
            }
            return Decision.HOLD;
        }

        if (attemptPending) {
            long sinceAttempt = nowMs - lastAttemptAtMs;
            if (sinceAttempt < settleMs || (playerMoving && sinceAttempt < maxInFlightMs)) {
                return Decision.IN_FLIGHT;
            }
            attemptPending = false;
            if (bestDistance >= distanceAtAttempt) {
                misses++;
            }
        }

        if (nowMs < holdUntilMs) {
            return Decision.HOLD;
        }

        if (misses >= missesPerStage[stage]) {
            misses = 0;
            if (stage >= backoffMs.length) {
                givenUp = true;
                givenUpAt = player;
                // Only a blocker seen when giving up can later be "gone"; otherwise the probe would
                // say "go" straight away and the guard would loop through its stages for ever.
                blockedWhenGivenUp = blockerGone != null && !blockerGone.getAsBoolean();
                lastBlockerProbeAtMs = nowMs;
                return Decision.STUCK;
            }
            holdUntilMs = nowMs + Math.round(backoffMs[stage] * jitter.getAsDouble());
            stage++;
            return Decision.HOLD;
        }
        return Decision.GO;
    }

    /** The walk was sent (a click went out, or a walkTo ran and didn't arrive). */
    synchronized void attempted(WorldPoint walkTarget, WorldPoint player, long nowMs) {
        if (walkTarget == null || player == null) {
            return;
        }
        if (!isSameTarget(walkTarget) || player.getPlane() != targetPlane) {
            track(walkTarget, player);
        }
        distanceAtAttempt = Math.min(bestDistance, distance(player, walkTarget));
        bestDistance = distanceAtAttempt;
        attemptPending = true;
        lastAttemptAtMs = nowMs;
    }

    /** The walk got there: forget everything about this target. */
    synchronized void arrived() {
        target = null;
        givenUp = false;
    }

    /** Whether the player was this close to the target at best (for "standing right next to it" cases). */
    synchronized int bestDistance() {
        return bestDistance;
    }

    synchronized boolean isGivenUp() {
        return givenUp;
    }

    private boolean isSameTarget(WorldPoint walkTarget) {
        return target != null
                && target.getPlane() == walkTarget.getPlane()
                && target.distanceTo2D(walkTarget) <= SAME_TARGET_TILES;
    }

    private void track(WorldPoint walkTarget, WorldPoint player) {
        target = walkTarget;
        targetPlane = player.getPlane();
        bestDistance = distance(player, walkTarget);
        distanceAtAttempt = bestDistance;
        attemptPending = false;
        lastAttemptAtMs = 0;
        misses = 0;
        stage = 0;
        holdUntilMs = 0;
        givenUp = false;
        givenUpAt = null;
        blockedWhenGivenUp = false;
        lastBlockerProbeAtMs = 0;
    }

    /** The walk was cancelled (script stopped, user took over): don't judge it. */
    synchronized void abandoned() {
        attemptPending = false;
    }

    /** Tiles to the target; a target on another plane counts as far. */
    private static int distance(WorldPoint player, WorldPoint walkTarget) {
        int flat = player.distanceTo2D(walkTarget);
        return player.getPlane() == walkTarget.getPlane() ? flat : 10_000 + flat;
    }
}
