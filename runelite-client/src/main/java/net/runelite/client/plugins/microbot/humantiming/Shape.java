package net.runelite.client.plugins.microbot.humantiming;

import java.util.Locale;
import java.util.Random;

/**
 * A right-skewed delay distribution, in milliseconds. Two families, both with a long right tail and
 * a density that fades smoothly to zero on the left (no wall):
 * <ul>
 *   <li><b>Shifted log-normal</b> (shift, median, sigma): {@code shift + (median - shift) * exp(sigma * Z)}.
 *       Used for self-paced gaps between actions (Plamondon's lognormality principle). The shift is the
 *       physiological minimum; the density is zero there and rises smoothly.</li>
 *   <li><b>Ex-Gaussian</b> (mu, sigma, tau): {@code Normal(mu, sigma) + Exponential(tau)}. The standard
 *       model for reaction times to a stimulus (Heathcote et al. 1991, Luce 1986).</li>
 * </ul>
 */
public final class Shape {
    public enum Family { SHIFTED_LOGNORMAL, EX_GAUSSIAN }

    private final Family family;
    private final double p1;
    private final double p2;
    private final double p3;

    private Shape(Family family, double p1, double p2, double p3) {
        this.family = family;
        this.p1 = p1;
        this.p2 = p2;
        this.p3 = p3;
    }

    /** Shifted log-normal. {@code sigma} is the standard deviation of log(x - shift); 0.3-0.6 is typical. */
    public static Shape lognormal(double shiftMs, double medianMs, double sigma) {
        if (!(medianMs > shiftMs) || !(sigma > 0)) {
            throw new IllegalArgumentException("need median > shift and sigma > 0: " + shiftMs + ", " + medianMs + ", " + sigma);
        }
        return new Shape(Family.SHIFTED_LOGNORMAL, shiftMs, medianMs, sigma);
    }

    /** Ex-Gaussian: Gaussian part (mu, sigma) plus an exponential tail with mean {@code tau}. */
    public static Shape exGaussian(double muMs, double sigmaMs, double tauMs) {
        if (!(sigmaMs > 0) || !(tauMs > 0)) {
            throw new IllegalArgumentException("need sigma > 0 and tau > 0: " + sigmaMs + ", " + tauMs);
        }
        return new Shape(Family.EX_GAUSSIAN, muMs, sigmaMs, tauMs);
    }

    /**
     * One sample.
     *
     * @param z      a standard-normal draw (may be serially correlated by the caller)
     * @param scale  tempo multiplier (speed, fatigue, practice, attention); stretches the variable part
     * @param spread multiplier on the log-sd / Gaussian sd (per-person consistency)
     */
    double sample(Random rng, double z, double scale, double spread) {
        if (family == Family.SHIFTED_LOGNORMAL) {
            return p1 + (p2 - p1) * scale * Math.exp(p3 * spread * z);
        }
        double exponential = -p3 * Math.log(1 - rng.nextDouble());
        return scale * (p1 + p2 * spread * z + exponential);
    }

    public Family family() {
        return family;
    }

    /** Log-normal: shift. Ex-Gaussian: mu. */
    public double first() {
        return p1;
    }

    /** Log-normal: median. Ex-Gaussian: sigma. */
    public double second() {
        return p2;
    }

    /** Log-normal: sigma of the log. Ex-Gaussian: tau. */
    public double third() {
        return p3;
    }

    public double mean() {
        if (family == Family.SHIFTED_LOGNORMAL) return p1 + (p2 - p1) * Math.exp(p3 * p3 / 2);
        return p1 + p3;
    }

    public double sd() {
        if (family == Family.SHIFTED_LOGNORMAL) {
            double w = Math.exp(p3 * p3);
            return (p2 - p1) * Math.sqrt(w * (w - 1));
        }
        return Math.sqrt(p2 * p2 + p3 * p3);
    }

    @Override
    public String toString() {
        if (family == Family.SHIFTED_LOGNORMAL) {
            return String.format(Locale.ROOT, "lognormal(shift=%.0fms, median=%.0fms, sigma=%.3f)", p1, p2, p3);
        }
        return String.format(Locale.ROOT, "exGaussian(mu=%.0fms, sigma=%.0fms, tau=%.0fms)", p1, p2, p3);
    }
}
