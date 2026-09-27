package net.runelite.client.plugins.microbot.humanmouse;

/**
 * Something to click, in canvas pixels: a rectangle (widget, inventory slot) or a polygon (the
 * clickbox of an object or NPC). Immutable.
 *
 * <p>People aim at the visual centre of what they see, not at its bounding box: the area centroid
 * of the shape, or for a concave shape (an L, a plus, a thin sprite) the middle of its largest
 * "fat" part (Grossman, Kong and Balakrishnan 2007). {@link #aimPoint()} gives that point.
 */
public final class Target {
    private final double[] xs;
    private final double[] ys;
    private final double minX, minY, maxX, maxY;
    private final double aimX, aimY;
    private final double width, height;

    private Target(double[] xs, double[] ys) {
        if (xs.length != ys.length || xs.length < 3) throw new IllegalArgumentException("need at least 3 vertices");
        this.xs = xs.clone();
        this.ys = ys.clone();
        double x0 = Double.MAX_VALUE, y0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            x0 = Math.min(x0, xs[i]);
            y0 = Math.min(y0, ys[i]);
            x1 = Math.max(x1, xs[i]);
            y1 = Math.max(y1, ys[i]);
        }
        minX = x0;
        minY = y0;
        maxX = x1;
        maxY = y1;
        double[] aim = findAimPoint();
        aimX = aim[0];
        aimY = aim[1];
        // Size along each screen axis is the chord through the aim point, not the bounding box: for
        // a diamond or a round clickbox that is what the hand has to land in.
        double w = chord(aimX, aimY, 1, 0);
        double h = chord(aimX, aimY, 0, 1);
        width = w > 0 ? w : maxX - minX;
        height = h > 0 ? h : maxY - minY;
    }

    /** A rectangle with its top-left corner at (x, y). Canvas pixels, like {@code java.awt.Rectangle}. */
    public static Target rect(double x, double y, double w, double h) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("empty rectangle");
        return new Target(new double[]{x, x + w, x + w, x}, new double[]{y, y, y + h, y + h});
    }

    /** A simple polygon (convex or not), e.g. a clickbox's convex hull. */
    public static Target polygon(double[] xs, double[] ys) {
        return new Target(xs, ys);
    }

    /** Integer vertices, as in {@code java.awt.Polygon}. */
    public static Target polygon(int[] xs, int[] ys, int n) {
        double[] dx = new double[n];
        double[] dy = new double[n];
        for (int i = 0; i < n; i++) {
            dx[i] = xs[i];
            dy[i] = ys[i];
        }
        return new Target(dx, dy);
    }

    /** Even-odd point-in-polygon test. */
    public boolean contains(double x, double y) {
        if (x < minX || x > maxX || y < minY || y > maxY) return false;
        boolean in = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > y) != (ys[j] > y)
                    && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                in = !in;
            }
        }
        return in;
    }

    public double aimX() { return aimX; }
    public double aimY() { return aimY; }
    /** Horizontal chord through the aim point, px: the width the hand has to hit. */
    public double width() { return width; }
    /** Vertical chord through the aim point, px. */
    public double height() { return height; }
    public double minX() { return minX; }
    public double minY() { return minY; }
    public double maxX() { return maxX; }
    public double maxY() { return maxY; }

    /** Size of the target in the direction (ux, uy): the chord through the aim point. */
    public double sizeAlong(double ux, double uy) {
        double c = chord(aimX, aimY, ux, uy);
        return c > 0 ? c : Math.min(width, height);
    }

    /** Distance from (x, y) to the nearest edge; positive inside, negative outside. */
    public double signedEdgeDistance(double x, double y) {
        double best = Double.MAX_VALUE;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            best = Math.min(best, segmentDistance(x, y, xs[j], ys[j], xs[i], ys[i]));
        }
        return contains(x, y) ? best : -best;
    }

    /**
     * The area centroid when it lies well inside the shape; otherwise the pole of inaccessibility
     * (the interior point furthest from every edge), which sits in the middle of the fattest part.
     */
    private double[] findAimPoint() {
        double a = 0, cx = 0, cy = 0;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            double cross = xs[j] * ys[i] - xs[i] * ys[j];
            a += cross;
            cx += (xs[j] + xs[i]) * cross;
            cy += (ys[j] + ys[i]) * cross;
        }
        if (Math.abs(a) < 1e-9) return new double[]{(minX + maxX) / 2, (minY + maxY) / 2};
        cx /= 3 * a;
        cy /= 3 * a;
        double[] pole = poleOfInaccessibility();
        double centroidDepth = signedEdgeDistance(cx, cy);
        // Convex shapes: the centroid is deep inside, keep it. Concave ones: the centroid can sit in a
        // thin arm or outside entirely; people then aim at the fat part.
        if (centroidDepth >= 0.6 * pole[2]) return new double[]{cx, cy};
        return new double[]{pole[0], pole[1]};
    }

    /** Coarse-to-fine grid search; {x, y, depth}. Accurate to well under a pixel for game-sized shapes. */
    private double[] poleOfInaccessibility() {
        double bx = (minX + maxX) / 2, by = (minY + maxY) / 2, bd = signedEdgeDistance(bx, by);
        double x0 = minX, y0 = minY, x1 = maxX, y1 = maxY;
        for (int round = 0; round < 6; round++) {
            int n = 24;
            double sx = (x1 - x0) / n, sy = (y1 - y0) / n;
            for (int i = 0; i <= n; i++) {
                for (int j = 0; j <= n; j++) {
                    double x = x0 + i * sx, y = y0 + j * sy;
                    double d = signedEdgeDistance(x, y);
                    if (d > bd) {
                        bd = d;
                        bx = x;
                        by = y;
                    }
                }
            }
            double rx = 2 * sx, ry = 2 * sy;
            x0 = bx - rx;
            x1 = bx + rx;
            y0 = by - ry;
            y1 = by + ry;
        }
        return new double[]{bx, by, bd};
    }

    /** Length of the line through (x, y) in direction (ux, uy) that lies inside the shape, around (x, y). */
    private double chord(double x, double y, double ux, double uy) {
        double len = Math.hypot(ux, uy);
        if (len == 0 || !contains(x, y)) return 0;
        ux /= len;
        uy /= len;
        return rayExit(x, y, ux, uy) + rayExit(x, y, -ux, -uy);
    }

    /** Distance from an inside point to the first edge crossing along the ray. */
    private double rayExit(double x, double y, double ux, double uy) {
        double best = Double.MAX_VALUE;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            double ex = xs[i] - xs[j], ey = ys[i] - ys[j];
            double den = ux * ey - uy * ex;
            if (Math.abs(den) < 1e-12) continue;
            double wx = xs[j] - x, wy = ys[j] - y;
            double t = (wx * ey - wy * ex) / den;
            double s = (wx * uy - wy * ux) / den;
            if (t > 1e-9 && s >= 0 && s <= 1) best = Math.min(best, t);
        }
        return best == Double.MAX_VALUE ? 0 : best;
    }

    private static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }
}
