package net.runelite.client.plugins.microbot.humanmouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Cursor movement from one point to another, as the hand does it.
 *
 * <p>A movement is a sum of log-normal strokes (the sigma-lognormal model of Plamondon's Kinematic
 * Theory): each stroke adds a displacement whose speed rises fast and falls slowly, so the speed
 * peak comes early and there is a long homing phase. Strokes overlap in time and simply add up, so
 * the cursor always ends exactly on the planned point.
 *
 * <ol>
 *   <li>A primary stroke aimed a little short (reach ~0.95 of the distance), with an endpoint error
 *       that grows with distance: SD 0.06 x A along the movement, 0.03 x A across (Grossman and
 *       Balakrishnan 2005), scaled by the player's {@link MouseProfile#primaryError()}.</li>
 *   <li>If that lands close enough to the click point, it is the whole movement. Otherwise one or
 *       two corrective strokes (100-250 ms each) take over, often before the first has stopped.</li>
 *   <li>The primary bows sideways: horizontal moves arc towards the top of the screen (the wrist
 *       pivots; Yamanaka 2018), plus a per-move random bow and a smaller S-bend. A slow sideways
 *       wander of about a pixel rides on top of long moves.</li>
 *   <li>Positions are reported at the mouse's polling rate with jittered intervals, as integers, and
 *       only when the pixel changes: a resting mouse sends nothing.</li>
 * </ol>
 */
final class PathModel {
    /** Primary endpoint SD as a fraction of distance: along and across the movement. */
    static final double PRIMARY_ALONG = 0.06;
    static final double PRIMARY_ACROSS = 0.03;
    /** Primary stroke gets this share of the Fitts movement time. */
    static final double PRIMARY_SHARE = 0.80;
    static final double MT_NOISE = 0.27;
    /** A primary landing within this share of the target size (or 2.5 px) needs no visible correction. */
    static final double NO_CORRECTION_SHARE = 0.30;
    static final double NO_CORRECTION_MIN_PX = 2.5;
    /** Even then, sometimes a small homing refinement anyway (the selection phase is ~35% of trial time). */
    static final double REFINE_P = 0.2;
    /** Residuals larger than this share of the target size take an intermediate correction. */
    static final double SECOND_CORRECTION_SHARE = 0.8;
    static final double SECOND_CORRECTION_MIN_PX = 15;
    static final double CORRECTION_ERROR = 0.07;
    static final int MAX_CORRECTIONS = 3;
    /** Chance a correction starts only after a short stop, rather than overlapping the previous stroke. */
    static final double CORRECTION_PAUSE_P = 0.65;
    /** Random sideways bow, SD as a fraction of distance. */
    static final double ARC_NOISE = 0.04;
    /** S-shaped bend (a path that crosses its own axis), SD as a fraction of distance. */
    static final double S_BEND = 0.012;
    static final double WANDER_TAU_MS = 70;
    static final double WANDER_MIN_PX = 40;
    /** Rightward moves are slower, upward a little faster (Phillips and Triggs 2001; Gianvecchio et al. 2009). */
    static final double RIGHTWARD_SLOWER = 0.07;
    static final double DOWNWARD_SLOWER = 0.03;
    /** z at which a stroke counts as started / finished (0.5% and 99.5% of its displacement). */
    static final double SPAN_Z = 2.576;
    static final double END_Z = 3.0;

    private final MouseProfile profile;
    private final Random rng;

    PathModel(MouseProfile profile, Random rng) {
        this.profile = profile;
        this.rng = rng;
    }

    /** One log-normal stroke: displacement (dx, dy) with speed log-normal in (t - t0). */
    static final class Stroke {
        final double t0, mu, sigma, dx, dy;

        Stroke(double start, double duration, double sigma, double dx, double dy) {
            this.sigma = sigma;
            this.mu = Math.log(duration / (2 * Math.sinh(SPAN_Z * sigma)));
            this.t0 = start - Math.exp(mu - SPAN_Z * sigma);
            this.dx = dx;
            this.dy = dy;
        }

        /** Share of the displacement done at time t. */
        double progress(double t) {
            if (t <= t0) return 0;
            return phi((Math.log(t - t0) - mu) / sigma);
        }

        /** Time at which progress reaches p. */
        double timeAt(double p) {
            return t0 + Math.exp(mu + sigma * inversePhi(p));
        }

        double end() {
            return t0 + Math.exp(mu + END_Z * sigma);
        }
    }

    /** A planned movement: strokes plus the sideways bow of the primary. */
    static final class Motion {
        final double sx, sy;
        final List<Stroke> strokes = new ArrayList<>();
        double bow;
        double bend;
        double nx, ny;
        double wander;
        boolean overshot;
        boolean aimedWithoutCorrection;

        Motion(double sx, double sy) {
            this.sx = sx;
            this.sy = sy;
        }

        double end() {
            double e = 0;
            for (Stroke s : strokes) e = Math.max(e, s.end());
            return e;
        }

        int submovements() {
            return strokes.size();
        }
    }

    /**
     * Plans a movement from (sx, sy) that ends exactly on (ex, ey). {@code sizeAlong} is the target's
     * size in the direction of travel (px) and sets the difficulty; {@code timeScale} multiplies the
     * movement time (pace).
     */
    Motion plan(double sx, double sy, double ex, double ey, double sizeAlong, double timeScale) {
        Motion m = new Motion(sx, sy);
        double dx = ex - sx, dy = ey - sy;
        double a = Math.hypot(dx, dy);
        if (a < 0.5) return m;
        double ux = dx / a, uy = dy / a;
        double nx = -uy, ny = ux;
        double w = Math.max(2, sizeAlong);

        // Primary stroke: aimed short, with a distance-proportional endpoint error.
        double along = a * (profile.reach() - 1) + PRIMARY_ALONG * profile.primaryError() * a * rng.nextGaussian();
        double across = PRIMARY_ACROSS * profile.primaryError() * a * rng.nextGaussian();
        double px = ex + ux * along + nx * across;
        double py = ey + uy * along + ny * across;
        double residual = Math.hypot(ex - px, ey - py);
        double noCorrection = Math.max(NO_CORRECTION_MIN_PX, NO_CORRECTION_SHARE * w);
        if (residual <= noCorrection && rng.nextDouble() >= REFINE_P) {
            // Close enough that the hand just finishes the stroke onto the spot.
            px = ex;
            py = ey;
            m.aimedWithoutCorrection = true;
        }
        // Overshoot as motor studies count it: the primary ends past the aimed point along the axis.
        m.overshot = along > 0;

        double bits = Math.log(a / w + 1) / Math.log(2);
        double direction = (1 + RIGHTWARD_SLOWER * Math.max(0, ux)) * (1 + DOWNWARD_SLOWER * uy);
        double mt = (profile.fittsA() + profile.fittsB() * bits) * timeScale * direction
                * Math.exp(MT_NOISE * rng.nextGaussian());
        double sigma1 = clamp(profile.strokeSigma() * Math.exp(0.12 * rng.nextGaussian()), 0.12, 0.45);
        Stroke primary = new Stroke(0, Math.max(60, PRIMARY_SHARE * mt), sigma1, px - sx, py - sy);
        m.strokes.add(primary);

        // Sideways bow of the primary: horizontal moves bulge towards the top of the screen.
        double upward = ny < 0 ? 1 : -1; // sign that makes the normal point up (screen y grows down)
        double pa = Math.hypot(px - sx, py - sy);
        m.nx = pa > 0 ? -(py - sy) / pa : nx;
        m.ny = pa > 0 ? (px - sx) / pa : ny;
        m.bow = a * (upward * profile.arc() * Math.abs(ux) + ARC_NOISE * rng.nextGaussian());
        m.bend = a * S_BEND * rng.nextGaussian();
        m.wander = a >= WANDER_MIN_PX ? profile.wanderPx() : 0;

        // Corrections: each starts during the previous stroke's slow-down, or after a brief stop.
        double cx = px, cy = py;
        Stroke prev = primary;
        while (m.strokes.size() <= MAX_CORRECTIONS) {
            double r = Math.hypot(ex - cx, ey - cy);
            if (r < 0.5) break;
            double tx = ex, ty = ey;
            boolean last = r <= Math.max(SECOND_CORRECTION_MIN_PX, SECOND_CORRECTION_SHARE * w)
                    || m.strokes.size() == MAX_CORRECTIONS;
            if (!last) {
                double vx = (ex - cx) / r, vy = (ey - cy) / r;
                double e1 = CORRECTION_ERROR * r * rng.nextGaussian();
                double e2 = 0.5 * CORRECTION_ERROR * r * rng.nextGaussian();
                tx = ex + vx * e1 - vy * e2;
                ty = ey + vy * e1 + vx * e2;
            }
            double start = rng.nextDouble() < CORRECTION_PAUSE_P
                    ? prev.timeAt(0.97) + lognormal(70, 0.5)
                    : prev.timeAt(0.88 + 0.10 * rng.nextDouble());
            double cbits = Math.log(r / w + 1) / Math.log(2);
            double dur = (110 + 45 * cbits) * timeScale * Math.exp(0.2 * rng.nextGaussian());
            double sig = 0.20 + 0.15 * rng.nextDouble();
            Stroke c = new Stroke(start, dur, sig, tx - cx, ty - cy);
            m.strokes.add(c);
            prev = c;
            cx = tx;
            cy = ty;
            if (last) break;
        }
        return m;
    }

    /** Bounding box of the planned path without the pixel-sized wander: {minX, minY, maxX, maxY}. */
    double[] extent(Motion m) {
        double[] box = {m.sx, m.sy, m.sx, m.sy};
        double end = m.end();
        for (double t = 0; t <= end; t += 5) {
            double[] p = position(m, t);
            box[0] = Math.min(box[0], p[0]);
            box[1] = Math.min(box[1], p[1]);
            box[2] = Math.max(box[2], p[0]);
            box[3] = Math.max(box[3], p[1]);
        }
        return box;
    }

    /** Planned position at time t: strokes plus the primary's bow and bend. */
    private static double[] position(Motion m, double t) {
        double x = m.sx, y = m.sy;
        for (Stroke s : m.strokes) {
            double p = s.progress(t);
            x += s.dx * p;
            y += s.dy * p;
        }
        if (!m.strokes.isEmpty()) {
            double p1 = m.strokes.get(0).progress(t);
            double side = m.bow * Math.sin(Math.PI * p1) + m.bend * Math.sin(2 * Math.PI * p1);
            x += m.nx * side;
            y += m.ny * side;
        }
        return new double[]{x, y};
    }

    /**
     * Writes the movement into {@code b} starting at time {@code t0Ms}; returns the time the cursor
     * settles on the end point. The start position must already be in {@code b}.
     */
    long render(MousePlan.Builder b, long t0Ms, Motion m) {
        if (m.strokes.isEmpty()) return t0Ms;
        double end = m.end();
        double wander = 0;
        double t = 0;
        double lastT = 0;
        double ex = m.sx, ey = m.sy;
        for (Stroke s : m.strokes) {
            ex += s.dx;
            ey += s.dy;
        }
        while (t < end) {
            double dt = profile.pollMs() * gamma16();
            t += dt;
            if (t >= end) break;
            double[] pos = position(m, t);
            double x = pos[0], y = pos[1];
            double envelope = Math.sin(Math.PI * m.strokes.get(0).progress(t));
            if (m.wander > 0) {
                // Ornstein-Uhlenbeck: stationary SD = m.wander, faded in and out with the primary.
                double k = Math.exp(-(t - lastT) / WANDER_TAU_MS);
                wander = k * wander + m.wander * Math.sqrt(1 - k * k) * rng.nextGaussian();
                x += m.nx * wander * envelope;
                y += m.ny * wander * envelope;
            }
            lastT = t;
            b.move(t0Ms + Math.round(t), (int) Math.round(x), (int) Math.round(y));
        }
        long settle = t0Ms + Math.round(end);
        b.move(settle, (int) Math.round(ex), (int) Math.round(ey));
        return Math.max(settle, b.lastT());
    }

    private double gamma16() {
        // Gamma(k = 16, mean 1): report intervals jitter by ~25% like real USB polling plus OS scheduling.
        double sum = 0;
        for (int i = 0; i < 16; i++) sum -= Math.log(1 - rng.nextDouble());
        return sum / 16;
    }

    double lognormal(double median, double sigma) {
        return median * Math.exp(sigma * rng.nextGaussian());
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Standard normal CDF (Abramowitz and Stegun 7.1.26 via erf, error < 1.5e-7). */
    static double phi(double z) {
        double x = Math.abs(z) / Math.sqrt(2);
        double t = 1 / (1 + 0.3275911 * x);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return z >= 0 ? 0.5 * (1 + y) : 0.5 * (1 - y);
    }

    /** Inverse standard normal CDF (Acklam's rational approximation, relative error < 1.2e-9). */
    static double inversePhi(double p) {
        if (p <= 0) return -8;
        if (p >= 1) return 8;
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double lo = 0.02425, hi = 1 - lo;
        if (p < lo) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > hi) {
            double q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5, r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
