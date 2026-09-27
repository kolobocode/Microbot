package net.runelite.client.plugins.microbot.humanmouse;

import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Human-looking mouse input for one player: where clicks land, how the cursor gets there, what it
 * does around the press, and what the hand does while waiting.
 *
 * <p>Every method returns a {@link MousePlan}: timed moves, presses and releases. Nothing is sent to
 * the game here; an adapter plays the plan back. What happens <i>between</i> actions (think time)
 * is HumanTiming's job; a plan starts at movement onset.
 *
 * <pre>
 * HumanMouse mouse = new HumanMouse(MouseProfile.fromName(playerName));
 * MousePlan plan = mouse.click(cursorX, cursorY, Target.rect(slot.x, slot.y, slot.width, slot.height),
 *         Pace.NORMAL, "inv:" + slotIndex, SafeMiss.NEVER);
 * // play plan.steps(); the button goes down at plan.finalPress()
 * </pre>
 *
 * <p>Not thread-safe; use one instance per player from the script thread.
 */
public final class HumanMouse {
    /** Dwell from the cursor settling to the press: log-normal around the profile's median. */
    static final double DWELL_SIGMA = 0.35;
    /** Button hold SD, ms (click duration ~ N(110, 30) in 14k CAPTCHA sessions; Choudhary et al. 2026). */
    static final double HOLD_SD_MS = 30;
    /** Share of clicks where the cursor slips a pixel or two while the button is down. */
    static final double PRESS_DRIFT_P = 0.25;
    /** Noticing a miss and starting the re-click: feedback 135-290 ms plus re-planning. */
    static final double MISS_NOTICE_MEDIAN_MS = 380;
    static final double MISS_NOTICE_SIGMA = 0.3;
    /** Extra look when the cursor stopped off the target and the player corrects before pressing. */
    static final double VERIFY_MEDIAN_MS = 170;
    static final double VERIFY_SIGMA = 0.4;
    /** Plain moves (hover, park) aim at a patch this size: nobody aims a hover to the pixel. */
    static final double LOOSE_TARGET_PX = 40;
    /** Idle drift spells take their time. */
    static final double IDLE_TIME_SCALE = 1.15;
    static final double IDLE_MOVE_MEAN_MS = 550;
    static final double IDLE_STILL_SIGMA = 0.8;
    static final int INSIDE_TRIES = 40;

    private final MouseProfile profile;
    private final Random rng;
    private final ClickPoints clicks;
    private final PathModel paths;

    public HumanMouse(MouseProfile profile) {
        this(profile, new Random(), System::currentTimeMillis);
    }

    /** Explicit random source and clock (the clock only ages habitual click spots), for tests. */
    public HumanMouse(MouseProfile profile, Random rng, LongSupplier clockMs) {
        this.profile = profile;
        this.rng = rng;
        this.clicks = new ClickPoints(profile, rng, clockMs);
        this.paths = new PathModel(profile, rng);
    }

    public MouseProfile profile() {
        return profile;
    }

    /** A normal-paced click with no habitual spot and no misses. */
    public MousePlan click(int fromX, int fromY, Target target) {
        return click(fromX, fromY, target, Pace.NORMAL, null, SafeMiss.NEVER);
    }

    /**
     * Move to {@code target} and click it.
     *
     * @param habitKey names the target across calls ("inv:12") so repeated clicks keep a habitual
     *                 spot; null for one-off targets
     * @param safeMiss where a missed click is harmless. A click point that falls off the target is
     *                 pressed there only if this says so, then noticed and re-clicked; otherwise the
     *                 cursor is corrected onto the target before the press
     */
    public MousePlan click(int fromX, int fromY, Target target, Pace pace, String habitKey, SafeMiss safeMiss) {
        MousePlan.Builder b = new MousePlan.Builder().start(fromX, fromY);
        double[] p = clicks.sample(target, fromX, fromY, pace, habitKey);
        int ex = (int) Math.round(p[0]), ey = (int) Math.round(p[1]);
        boolean on = onTarget(target, ex, ey);

        PathModel.Motion m = paths.plan(fromX, fromY, ex, ey, sizeToward(target, fromX, fromY), pace.time);
        b.submovements = m.submovements();
        b.overshot = m.overshot;
        long t = paths.render(b, 0, m);

        if (!on) {
            if (safeMiss != null && safeMiss.isHarmless(ex, ey)) {
                t = press(b, t, false);
                t += Math.round(lognormal(MISS_NOTICE_MEDIAN_MS, MISS_NOTICE_SIGMA));
            } else {
                t += Math.round(dwell() + lognormal(VERIFY_MEDIAN_MS, VERIFY_SIGMA));
                b.correctedBeforePress = true;
            }
            int[] q = pointInside(target, ex, ey);
            PathModel.Motion fix = paths.plan(ex, ey, q[0], q[1], sizeToward(target, ex, ey), Pace.CAREFUL.time);
            b.submovements += fix.submovements();
            t = paths.render(b, t, fix);
        }
        press(b, t, true);
        return b.build();
    }

    /** Moves the cursor to about (toX, toY) without clicking: hovering, parking the mouse. */
    public MousePlan move(int fromX, int fromY, int toX, int toY) {
        MousePlan.Builder b = new MousePlan.Builder().start(fromX, fromY);
        PathModel.Motion m = paths.plan(fromX, fromY, toX, toY, LOOSE_TARGET_PX, 1);
        b.submovements = m.submovements();
        b.overshot = m.overshot;
        paths.render(b, 0, m);
        return b.build();
    }

    /**
     * What the hand does during a wait of {@code durationMs}: mostly nothing (the mouse sends no
     * events while it rests), now and then a small reposition, a drift towards the next target if
     * known, rarely a longer move. Moves stay inside {@code area} (the game canvas).
     *
     * @param next the target the player expects to click next, or null
     */
    public MousePlan idle(int fromX, int fromY, long durationMs, Target area, Target next) {
        MousePlan.Builder b = new MousePlan.Builder().start(fromX, fromY);
        double still = profile.stillShare();
        // Still spells: log-normal, sized so the long-run resting share matches the profile.
        // The first spell is sized from a typical move; later ones from the move just made.
        double stillPerMoveMs = still / Math.max(0.05, 1 - still);
        double lastMoveMs = IDLE_MOVE_MEAN_MS;
        double cx = fromX, cy = fromY;
        boolean drifted = false;
        long t = 0;
        while (true) {
            double medianStill = stillPerMoveMs * lastMoveMs / Math.exp(IDLE_STILL_SIGMA * IDLE_STILL_SIGMA / 2);
            t += Math.round(lognormal(medianStill, IDLE_STILL_SIGMA));
            if (t >= durationMs) break;
            double r = rng.nextDouble();
            double tx, ty;
            if (next != null && !drifted && r < 0.35) {
                double f = 0.5 + 0.4 * rng.nextDouble();
                tx = cx + f * (next.aimX() - cx) + 0.15 * next.width() * rng.nextGaussian();
                ty = cy + f * (next.aimY() - cy) + 0.15 * next.height() * rng.nextGaussian();
                drifted = true;
            } else {
                double dist = r < 0.9 ? Math.min(150, Math.max(8, lognormal(40, 0.6))) : 150 + 250 * rng.nextDouble();
                double ang = 2 * Math.PI * rng.nextDouble();
                tx = cx + dist * Math.cos(ang);
                ty = cy + dist * Math.sin(ang);
            }
            // Keep the destination far enough in that an overshoot or bow stays on the canvas.
            double margin = 6 + 0.2 * Math.hypot(tx - cx, ty - cy);
            tx = Math.max(area.minX() + margin, Math.min(area.maxX() - margin, tx));
            ty = Math.max(area.minY() + margin, Math.min(area.maxY() - margin, ty));
            PathModel.Motion m = paths.plan(cx, cy, Math.round(tx), Math.round(ty), LOOSE_TARGET_PX, IDLE_TIME_SCALE);
            if (t + m.end() >= durationMs) break;
            // A bow along the canvas edge could leave the window; this spell the hand just stays put.
            double[] box = paths.extent(m);
            if (box[0] < area.minX() + 4 || box[1] < area.minY() + 4 || box[2] > area.maxX() - 4 || box[3] > area.maxY() - 4) {
                continue;
            }
            long startMove = t;
            t = paths.render(b, t, m);
            lastMoveMs = Math.max(50, t - startMove);
            cx = Math.round(tx);
            cy = Math.round(ty);
        }
        return b.build();
    }

    /** Just the click point (rounded), for callers that move the mouse some other way. */
    public int[] clickPoint(int fromX, int fromY, Target target, Pace pace, String habitKey) {
        double[] p = clicks.sample(target, fromX, fromY, pace, habitKey);
        return new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])};
    }

    /** Pixel (x, y) is on the target if its centre is: matches java.awt.Rectangle.contains for rectangles. */
    static boolean onTarget(Target t, int x, int y) {
        return t.contains(x + 0.5, y + 0.5);
    }

    ClickPoints clickPoints() {
        return clicks;
    }

    PathModel paths() {
        return paths;
    }

    // ------------------------------------------------------------------------------------------

    /** Dwell, press, hold (with an occasional pixel of slip), release. Returns the release time. */
    private long press(MousePlan.Builder b, long settledMs, boolean onTarget) {
        long down = settledMs + Math.round(dwell());
        long up = down + Math.max(1, Math.round(hold()));
        b.press(down, up, onTarget);
        if (rng.nextDouble() < PRESS_DRIFT_P) {
            double u = rng.nextDouble();
            int px = u < 0.70 ? 1 : u < 0.95 ? 2 : 3;
            double ang = 2 * Math.PI * rng.nextDouble();
            long at = down + 1 + (long) ((up - down - 1) * rng.nextDouble());
            b.move(at, b.lastX() + (int) Math.round(px * Math.cos(ang)), b.lastY() + (int) Math.round(px * Math.sin(ang)));
        }
        b.release(up);
        return up;
    }

    private double dwell() {
        return lognormal(profile.dwellMedianMs(), DWELL_SIGMA);
    }

    /** Log-normal with the profile's mean and HOLD_SD_MS: positive, right-skewed, no floor. */
    private double hold() {
        double m = profile.holdMeanMs();
        double s2 = Math.log(1 + (HOLD_SD_MS * HOLD_SD_MS) / (m * m));
        return m * Math.exp(Math.sqrt(s2) * rng.nextGaussian() - s2 / 2);
    }

    /** A careful re-aim that lands on the target. */
    private int[] pointInside(Target target, int fromX, int fromY) {
        for (int i = 0; i < INSIDE_TRIES; i++) {
            double[] q = clicks.sample(target, fromX, fromY, Pace.CAREFUL, null);
            int qx = (int) Math.round(q[0]), qy = (int) Math.round(q[1]);
            if (onTarget(target, qx, qy)) return new int[]{qx, qy};
        }
        return new int[]{(int) Math.floor(target.aimX()), (int) Math.floor(target.aimY())};
    }

    private static double sizeToward(Target t, double fromX, double fromY) {
        double dx = t.aimX() - fromX, dy = t.aimY() - fromY;
        double d = Math.hypot(dx, dy);
        return d < 1 ? Math.min(t.width(), t.height()) : t.sizeAlong(dx / d, dy / d);
    }

    private double lognormal(double median, double sigma) {
        return median * Math.exp(sigma * rng.nextGaussian());
    }
}
