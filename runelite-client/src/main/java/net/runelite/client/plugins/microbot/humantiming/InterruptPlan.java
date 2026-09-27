package net.runelite.client.plugins.microbot.humantiming;

import java.util.Arrays;

/**
 * What a person does after something interrupts a run of actions (the rock depleted, the inventory
 * filled, the target died): a few already-committed actions still come out, then after a reaction
 * time they do something new.
 *
 * <pre>
 * interrupt ─ overshoot[0] ─▶ repeat ─ overshoot[1] ─▶ repeat ─ responseDelay ─▶ new action
 * </pre>
 *
 * The overshoot actions should be harmless repeats of what the player was doing (clicking the gone
 * rock again, one more drop click). If a repeat could be harmful, ask for {@code maxOvershoot = 0};
 * the response delay still covers the full human reaction.
 */
public final class InterruptPlan {
    private final long[] overshootDelaysMs;
    private final long responseDelayMs;
    private final long noticeMs;

    InterruptPlan(long[] overshootDelaysMs, long responseDelayMs, long noticeMs) {
        this.overshootDelaysMs = overshootDelaysMs;
        this.responseDelayMs = responseDelayMs;
        this.noticeMs = noticeMs;
    }

    /** Number of extra repeats before the player stops. Usually 0-3, depending on pace. */
    public int overshootCount() {
        return overshootDelaysMs.length;
    }

    /** Delay before each extra repeat; the first is measured from the interrupt, the rest from the previous repeat. */
    public long[] overshootDelaysMs() {
        return overshootDelaysMs.clone();
    }

    /** Delay from the last repeat (or from the interrupt if there were none) to the new action. */
    public long responseDelayMs() {
        return responseDelayMs;
    }

    /** Time from the interrupt until the player noticed it and stopped. */
    public long noticeMs() {
        return noticeMs;
    }

    /** Time from the interrupt to the new action. */
    public long totalMs() {
        long t = responseDelayMs;
        for (long d : overshootDelaysMs) t += d;
        return t;
    }

    @Override
    public String toString() {
        return "InterruptPlan{overshoot=" + Arrays.toString(overshootDelaysMs) + ", response=" + responseDelayMs
                + "ms, notice=" + noticeMs + "ms}";
    }
}
