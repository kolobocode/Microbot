package net.runelite.client.plugins.microbot.humanmouse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

/**
 * Where the button goes down inside a target.
 *
 * <ul>
 *   <li>Bivariate normal around the aim point. Nothing is clamped to the target: the edges fade out
 *       smoothly and a few clicks land just outside (Yamanaka 2023: 80-92% of conditions pass a
 *       normality test, 670k clicks).</li>
 *   <li>Spread per screen axis grows with the target's size on that axis:
 *       sigma = 0.177 * size + 2.03 px horizontally, + 1.00 px vertically (Yamanaka 2023, R^2 0.99),
 *       times the player's {@link MouseProfile#accuracy()} and the {@link Pace}.</li>
 *   <li>Stretched along the direction of approach: along / across SD ~ 1.22 (1.14-1.3 in Yamanaka
 *       and MacKenzie 2026), keeping the area of the spread the same.</li>
 *   <li>A per-player offset ({@link MouseProfile#biasX()}), a small shift towards the side the hand
 *       came from on large targets, and a rare slip (a wider draw).</li>
 *   <li>A habitual spot per familiar target: a slow AR(1) part of the spread, so repeated clicks on
 *       the same slot drift instead of being independent. Bigger targets leave more room for it.</li>
 * </ul>
 */
final class ClickPoints {
    static final double SIGMA_SLOPE = 0.177;
    static final double SIGMA_X_PX = 2.03;
    static final double SIGMA_Y_PX = 1.00;
    /** Along-approach SD over across SD. */
    static final double ANISOTROPY = 1.22;
    /** Share of clicks drawn from a wider distribution (a slip): studies drop 1.4-5% of trials as outliers. */
    static final double SLIP_P = 0.004;
    static final double SLIP_SCALE = 2.5;
    /** Shift towards the start: -APPROACH_SHIFT * size * size / (size + APPROACH_SIZE_PX), so ~0 on small targets. */
    static final double APPROACH_SHIFT = 0.04;
    static final double APPROACH_SIZE_PX = 60;
    /** Moves shorter than this have no meaningful approach direction. */
    static final double MIN_APPROACH_PX = 20;
    static final double HABIT_RHO = 0.8;
    static final long HABIT_FORGET_MS = 10 * 60_000;
    static final int MAX_HABITS = 256;

    private final MouseProfile profile;
    private final Random rng;
    private final LongSupplier clock;
    private final Map<String, double[]> habits = new LinkedHashMap<String, double[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
            return size() > MAX_HABITS;
        }
    };

    ClickPoints(MouseProfile profile, Random rng, LongSupplier clock) {
        this.profile = profile;
        this.rng = rng;
        this.clock = clock;
    }

    /** SD of clicks along x and y for this target, before the approach stretch. */
    double[] sigma(Target t, Pace pace) {
        double k = profile.accuracy() * pace.spread;
        return new double[]{k * (SIGMA_SLOPE * t.width() + SIGMA_X_PX), k * (SIGMA_SLOPE * t.height() + SIGMA_Y_PX)};
    }

    /**
     * One click point, not rounded. {@code habitKey} names the target across calls ("inv:12",
     * "bank:deposit-all"); null means no habitual spot.
     */
    double[] sample(Target t, double fromX, double fromY, Pace pace, String habitKey) {
        double[] s = sigma(t, pace);
        double ax = t.aimX(), ay = t.aimY();
        double dx = ax - fromX, dy = ay - fromY;
        double dist = Math.hypot(dx, dy);

        // Covariance diag(sx^2, sy^2), then stretch along the approach direction u (det kept).
        double cxx = s[0] * s[0], cxy = 0, cyy = s[1] * s[1];
        double mx = ax + profile.biasX() * t.width();
        double my = ay + profile.biasY() * t.height();
        if (dist >= MIN_APPROACH_PX) {
            double ux = dx / dist, uy = dy / dist;
            double a = Math.sqrt(ANISOTROPY), b = 1 / a;
            // S = a*u*u' + b*n*n', n = (-uy, ux)
            double s11 = a * ux * ux + b * uy * uy;
            double s12 = (a - b) * ux * uy;
            double s22 = a * uy * uy + b * ux * ux;
            // C' = S * diag(cxx, cyy) * S
            double n11 = s11 * s11 * cxx + s12 * s12 * cyy;
            double n12 = s11 * s12 * cxx + s12 * s22 * cyy;
            double n22 = s12 * s12 * cxx + s22 * s22 * cyy;
            cxx = n11;
            cxy = n12;
            cyy = n22;
            double size = t.sizeAlong(ux, uy);
            double shift = APPROACH_SHIFT * size * size / (size + APPROACH_SIZE_PX);
            mx -= ux * shift;
            my -= uy * shift;
        }
        // Cholesky of the 2x2 covariance.
        double l11 = Math.sqrt(cxx);
        double l21 = cxy / l11;
        double l22 = Math.sqrt(Math.max(1e-12, cyy - l21 * l21));

        double z1 = rng.nextGaussian(), z2 = rng.nextGaussian();
        if (rng.nextDouble() < SLIP_P) {
            z1 *= SLIP_SCALE;
            z2 *= SLIP_SCALE;
        }
        double share = habitShare(t, s);
        if (habitKey != null && share > 0) {
            double[] h = habit(habitKey);
            double keep = Math.sqrt(1 - share), hab = Math.sqrt(share);
            z1 = keep * z1 + hab * h[0];
            z2 = keep * z2 + hab * h[1];
        }
        return new double[]{mx + l11 * z1, my + l21 * z1 + l22 * z2};
    }

    /**
     * Share of the spread that is a habitual spot. Needs room: on a target only a few SDs wide every
     * click has to go for the middle, so repeated clicks there are nearly independent (dart experts:
     * lag-1 autocorrelation ~0, van Beers et al. 2013); on big targets the free axis drifts.
     */
    private double habitShare(Target t, double[] s) {
        double room = Math.min(t.width() / s[0], t.height() / s[1]);
        double slack = Math.max(0.15, Math.min(1, (room - 4) / 8));
        return profile.habit() * slack;
    }

    /** Advances and returns the AR(1) habitual offset for this target (unit normal per axis). */
    private double[] habit(String key) {
        long now = clock.getAsLong();
        double[] h = habits.get(key);
        if (h == null || now - (long) h[2] > HABIT_FORGET_MS) {
            h = new double[]{rng.nextGaussian(), rng.nextGaussian(), now};
            habits.put(key, h);
            return h;
        }
        double fresh = Math.sqrt(1 - HABIT_RHO * HABIT_RHO);
        h[0] = HABIT_RHO * h[0] + fresh * rng.nextGaussian();
        h[1] = HABIT_RHO * h[1] + fresh * rng.nextGaussian();
        h[2] = now;
        return h;
    }
}
