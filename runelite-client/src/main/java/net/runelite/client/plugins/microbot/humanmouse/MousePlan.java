package net.runelite.client.plugins.microbot.humanmouse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A timed sequence of mouse input: cursor moves, button presses and releases, with times in ms from
 * the start of the plan. Whoever plays it back (an adapter that dispatches AWT events) only has to
 * wait until each step's time and send it. Integer canvas pixels, like a real mouse.
 *
 * <p>Nothing here touches the game; the plan is data, so it can be checked offline.
 */
public final class MousePlan {
    public enum Kind { MOVE, PRESS, RELEASE }

    /** One input event. */
    public static final class Step {
        public final Kind kind;
        public final long tMs;
        public final int x;
        public final int y;

        Step(Kind kind, long tMs, int x, int y) {
            this.kind = kind;
            this.tMs = tMs;
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return kind + "@" + tMs + "(" + x + "," + y + ")";
        }
    }

    /** One button press: where it landed and whether that was on the target. */
    public static final class Press {
        public final int x;
        public final int y;
        public final long downMs;
        public final long upMs;
        public final boolean onTarget;

        Press(int x, int y, long downMs, long upMs, boolean onTarget) {
            this.x = x;
            this.y = y;
            this.downMs = downMs;
            this.upMs = upMs;
            this.onTarget = onTarget;
        }
    }

    private final List<Step> steps;
    private final List<Press> presses;
    private final int submovements;
    private final boolean overshot;
    private final boolean correctedBeforePress;

    MousePlan(List<Step> steps, List<Press> presses, int submovements, boolean overshot, boolean correctedBeforePress) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
        this.presses = Collections.unmodifiableList(new ArrayList<>(presses));
        this.submovements = submovements;
        this.overshot = overshot;
        this.correctedBeforePress = correctedBeforePress;
    }

    public List<Step> steps() { return steps; }

    /** Presses in order. A click plan has one, or two when the first missed and was re-clicked. */
    public List<Press> presses() { return presses; }

    /** The press that counts: the last one. Null for a plan without a click (a move or idle drift). */
    public Press finalPress() {
        return presses.isEmpty() ? null : presses.get(presses.size() - 1);
    }

    /** True when the first press landed off the target (a misclick the plan then re-clicks). */
    public boolean missed() {
        return !presses.isEmpty() && !presses.get(0).onTarget;
    }

    /** Number of submovements in the approach: 1 = one smooth stroke, more = corrections. */
    public int submovements() { return submovements; }

    /** True when the primary stroke went past the target before coming back. */
    public boolean overshot() { return overshot; }

    /** True when the cursor stopped off the target and was corrected before the button went down. */
    public boolean correctedBeforePress() { return correctedBeforePress; }

    /** Time of the last step, ms. */
    public long durationMs() {
        return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).tMs;
    }

    /** Cursor position at the end of the plan. */
    public int endX() { return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).x; }
    public int endY() { return steps.isEmpty() ? 0 : steps.get(steps.size() - 1).y; }

    /**
     * The cursor path as the game server sees it: the position every {@code periodMs} (the OSRS
     * client samples every 50 ms). Returns {x[], y[]} of the samples, starting at t = 0.
     */
    public int[][] sampled(long periodMs) {
        List<int[]> out = new ArrayList<>();
        int cx = steps.isEmpty() ? 0 : steps.get(0).x, cy = steps.isEmpty() ? 0 : steps.get(0).y;
        int i = 0;
        for (long t = 0; t <= durationMs(); t += periodMs) {
            while (i < steps.size() && steps.get(i).tMs <= t) {
                cx = steps.get(i).x;
                cy = steps.get(i).y;
                i++;
            }
            out.add(new int[]{cx, cy});
        }
        int[][] r = new int[2][out.size()];
        for (int k = 0; k < out.size(); k++) {
            r[0][k] = out.get(k)[0];
            r[1][k] = out.get(k)[1];
        }
        return r;
    }

    @Override
    public String toString() {
        return "MousePlan{" + steps.size() + " steps, " + durationMs() + " ms, presses=" + presses.size()
                + ", missed=" + missed() + ", submovements=" + submovements + "}";
    }

    /** Mutable builder used by the models. */
    static final class Builder {
        private final List<Step> steps = new ArrayList<>();
        private final List<Press> presses = new ArrayList<>();
        int submovements = 1;
        boolean overshot;
        boolean correctedBeforePress;
        private int lastX = Integer.MIN_VALUE, lastY = Integer.MIN_VALUE;
        private long lastT = -1;

        /** Adds a move if the pixel changed. Times never go backwards. */
        Builder move(long t, int x, int y) {
            t = Math.max(t, lastT);
            if (x == lastX && y == lastY) return this;
            steps.add(new Step(Kind.MOVE, t, x, y));
            lastX = x;
            lastY = y;
            lastT = t;
            return this;
        }

        /** Always records the starting position, even if it equals nothing before it. */
        Builder start(int x, int y) {
            steps.add(new Step(Kind.MOVE, 0, x, y));
            lastX = x;
            lastY = y;
            lastT = 0;
            return this;
        }

        Builder press(long down, long up, boolean onTarget) {
            down = Math.max(down, lastT);
            up = Math.max(up, down + 1);
            steps.add(new Step(Kind.PRESS, down, lastX, lastY));
            lastT = down;
            int px = lastX, py = lastY;
            // Moves during the hold are added by the caller between press() and release().
            presses.add(new Press(px, py, down, up, onTarget));
            return this;
        }

        Builder release(long up) {
            up = Math.max(up, lastT);
            steps.add(new Step(Kind.RELEASE, up, lastX, lastY));
            lastT = up;
            return this;
        }

        int lastX() { return lastX; }
        int lastY() { return lastY; }
        long lastT() { return lastT; }

        MousePlan build() {
            return new MousePlan(steps, presses, submovements, overshot, correctedBeforePress);
        }
    }
}
