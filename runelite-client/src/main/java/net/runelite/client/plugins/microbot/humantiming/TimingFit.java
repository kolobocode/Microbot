package net.runelite.client.plugins.microbot.humantiming;

import java.util.Arrays;
import java.util.Locale;

/**
 * Fits delay distributions to recorded human timings, so a profile can reproduce one real person
 * instead of population averages.
 *
 * <p><b>Rounded timestamps.</b> If your log rounds timestamps down to a step {@code Δ} (1000 ms for
 * "HH:mm:ss" logs, 600 ms if you only know the game tick), each timestamp carries uniform error of
 * variance Δ²/12, so each gap carries Δ²/6. A true spread of 0.378 s reads as 0.55 s with Δ = 1 s.
 * The fits here subtract that before fitting; pass {@code roundingMs = 0} for exact timestamps.
 * The mean is unaffected, and the error is symmetric so the third moment is unaffected too.
 */
public final class TimingFit {
    private TimingFit() {
    }

    /** Gaps between consecutive timestamps (ms). */
    public static double[] gaps(long[] timestampsMs) {
        long[] t = timestampsMs.clone();
        Arrays.sort(t);
        double[] g = new double[Math.max(0, t.length - 1)];
        for (int i = 1; i < t.length; i++) g[i - 1] = t[i] - t[i - 1];
        return g;
    }

    /**
     * Three-parameter (shifted) log-normal by the method of moments on rounding-corrected moments.
     * Falls back to an unshifted log-normal when the data is barely skewed.
     */
    public static Shape fitLognormal(double[] gapsMs, double roundingMs) {
        Summary s = Summary.of(gapsMs);
        double var = correctedVariance(s, roundingMs);
        double skew = s.thirdCentral / Math.pow(var, 1.5);
        if (skew > 0.05) {
            double w = solveLognormalW(skew);
            double k = Math.sqrt(var / (w * (w - 1))); // median - shift
            double shift = s.mean - k * Math.sqrt(w);
            if (shift >= 0) return Shape.lognormal(shift, shift + k, Math.sqrt(Math.log(w)));
        }
        double sigma2 = Math.log(1 + var / (s.mean * s.mean));
        return Shape.lognormal(0, s.mean / Math.exp(sigma2 / 2), Math.sqrt(sigma2));
    }

    /** Ex-Gaussian by the method of moments on rounding-corrected moments (use for reaction times). */
    public static Shape fitExGaussian(double[] gapsMs, double roundingMs) {
        Summary s = Summary.of(gapsMs);
        double var = correctedVariance(s, roundingMs);
        double sd = Math.sqrt(var);
        double skew = Math.max(0.01, Math.min(1.99, s.thirdCentral / Math.pow(var, 1.5)));
        double tau = sd * Math.cbrt(skew / 2);
        double sigma = Math.sqrt(Math.max(1, var - tau * tau));
        return Shape.exGaussian(s.mean - tau, sigma, tau);
    }

    /** Variance with the rounding noise (Δ²/6 per gap) removed. */
    public static double correctedVariance(Summary s, double roundingMs) {
        double v = s.sd * s.sd - roundingMs * roundingMs / 6;
        if (v <= 0) throw new IllegalArgumentException("spread is below the rounding noise; need finer timestamps");
        return v;
    }

    /** Skewness of a log-normal is (w + 2) * sqrt(w - 1) with w = exp(sigma²); solve for w. */
    private static double solveLognormalW(double skew) {
        double lo = 1;
        double hi = 2;
        while ((hi + 2) * Math.sqrt(hi - 1) < skew) hi *= 2;
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2;
            if ((mid + 2) * Math.sqrt(mid - 1) < skew) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /** Descriptive statistics of a sample. The quick human test: {@code mean > median}. */
    public static final class Summary {
        public final int n;
        public final double mean;
        public final double median;
        public final double sd;
        public final double skewness;
        public final double thirdCentral;
        public final double min;
        public final double p05;
        public final double p95;
        public final double max;

        private Summary(double[] x) {
            n = x.length;
            if (n < 3) throw new IllegalArgumentException("need at least 3 values");
            double[] s = x.clone();
            Arrays.sort(s);
            double sum = 0;
            for (double v : s) sum += v;
            mean = sum / n;
            double m2 = 0;
            double m3 = 0;
            for (double v : s) {
                double d = v - mean;
                m2 += d * d;
                m3 += d * d * d;
            }
            m2 /= n;
            thirdCentral = m3 / n;
            sd = Math.sqrt(m2 * n / (n - 1));
            skewness = thirdCentral / Math.pow(m2, 1.5);
            min = s[0];
            max = s[n - 1];
            median = quantile(s, 0.5);
            p05 = quantile(s, 0.05);
            p95 = quantile(s, 0.95);
        }

        public static Summary of(double[] x) {
            return new Summary(x);
        }

        private static double quantile(double[] sorted, double q) {
            double pos = q * (sorted.length - 1);
            int i = (int) Math.floor(pos);
            int j = Math.min(sorted.length - 1, i + 1);
            return sorted[i] + (pos - i) * (sorted[j] - sorted[i]);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT,
                    "n=%d mean=%.0f median=%.0f sd=%.0f skew=%.2f min=%.0f p05=%.0f p95=%.0f max=%.0f",
                    n, mean, median, sd, skewness, min, p05, p95, max);
        }
    }
}
