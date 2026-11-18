package com.irythill.rwg3.procgen.util.noise;

/** Smooth deterministic domain warp around another coherent point field. */
public final class Warped<T extends ICoherentNoise> implements ICoherentNoise {

    private final long seed;
    private final T delegate;
    private final double scale;
    private final double strength;
    private final ThreadLocal<double[]> queryWarp = ThreadLocal.withInitial(() -> new double[2]);

    public Warped(long seed, T delegate, double scale, double strength) {
        if (!(scale > 0.0) || strength < 0.0) throw new IllegalArgumentException("Invalid warp parameters");
        this.seed = seed;
        this.delegate = delegate;
        this.scale = scale;
        this.strength = strength;
    }

    @Override
    public void nearest(double x, double y, int count, Query result) {
        double[] warped = queryWarp.get();
        warp(x, y, warped);
        delegate.nearest(warped[0], warped[1], count, result);
    }

    public T delegate() {
        return delegate;
    }

    public void warp(double x, double y, double[] output) {
        double nx = x / scale, ny = y / scale;
        output[0] = x + fractalNoise(nx, ny, 0x243F6A8885A308D3L) * strength;
        output[1] = y + fractalNoise(nx, ny, 0x13198A2E03707344L) * strength;
    }

    /** Maps a delegate-space site center back to physical space and validates the round trip. */
    public void inverse(double targetX, double targetY, double[] output) {
        double[] value = new double[2], horizontal = new double[2], vertical = new double[2];
        double bestX = targetX, bestY = targetY, bestSquared = Double.POSITIVE_INFINITY;
        double tolerance = Math.max(1.0e-7, scale * 1.0e-8);
        double epsilon = Math.max(1.0e-6, scale * 1.0e-6);
        // A preimage cannot be farther than strength on either axis because the displacement
        // noise is in [-1, 1]. Multiple starts avoid assuming that this strong warp is globally
        // one-to-one or that the site itself lies in Newton's basin.
        int starts = strength == 0.0 ? 1 : 7;
        for (int startY = 0; startY < starts; startY++) {
            for (int startX = 0; startX < starts; startX++) {
                double x = targetX + (starts == 1 ? 0.0 : strength * (2.0 * startX / (starts - 1) - 1.0));
                double y = targetY + (starts == 1 ? 0.0 : strength * (2.0 * startY / (starts - 1) - 1.0));
                for (int iteration = 0; iteration < 40; iteration++) {
                    warp(x, y, value);
                    double ex = value[0] - targetX, ey = value[1] - targetY;
                    double squared = ex * ex + ey * ey;
                    if (squared < bestSquared) {
                        bestSquared = squared;
                        bestX = x;
                        bestY = y;
                    }
                    if (squared <= tolerance * tolerance) {
                        output[0] = x;
                        output[1] = y;
                        return;
                    }
                    warp(x + epsilon, y, horizontal);
                    warp(x, y + epsilon, vertical);
                    double a = (horizontal[0] - value[0]) / epsilon;
                    double b = (vertical[0] - value[0]) / epsilon;
                    double c = (horizontal[1] - value[1]) / epsilon;
                    double d = (vertical[1] - value[1]) / epsilon;
                    double determinant = a * d - b * c;
                    double sx = Math.abs(determinant) < 1.0e-10 ? ex : (d * ex - b * ey) / determinant;
                    double sy = Math.abs(determinant) < 1.0e-10 ? ey : (-c * ex + a * ey) / determinant;
                    double length = Math.hypot(sx, sy), limit = scale * 0.5;
                    if (length > limit) {
                        sx *= limit / length;
                        sy *= limit / length;
                    }
                    // Backtracking prevents Newton from abandoning a useful basin.
                    double factor = 1.0;
                    while (factor > 1.0 / 64.0) {
                        warp(x - sx * factor, y - sy * factor, horizontal);
                        double nx = horizontal[0] - targetX, ny = horizontal[1] - targetY;
                        if (nx * nx + ny * ny < squared) break;
                        factor *= 0.5;
                    }
                    x -= sx * factor;
                    y -= sy * factor;
                }
            }
        }
        double error = Math.sqrt(bestSquared);
        if (error > tolerance) throw new IllegalStateException("Warp inverse failed round-trip: " + error);
        output[0] = bestX;
        output[1] = bestY;
    }

    private double fractalNoise(double x, double y, long salt) {
        return valueNoise(x, y, salt) * 0.72 + valueNoise(x * 2.0, y * 2.0, salt ^ 0x9E3779B97F4A7C15L) * 0.28;
    }

    private double valueNoise(double x, double y, long salt) {
        int x0 = floor(x), y0 = floor(y);
        double tx = fade(x - x0), ty = fade(y - y0);
        double a = signed(hash(x0, y0, salt)), b = signed(hash(x0 + 1, y0, salt));
        double c = signed(hash(x0, y0 + 1, salt)), d = signed(hash(x0 + 1, y0 + 1, salt));
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    private long hash(int x, int y, long salt) {
        long value = seed ^ (long) x * 341873128712L ^ (long) y * 132897987541L ^ salt;
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private static double signed(long v) {
        return (v >>> 11) * 0x1.0p-52 - 1.0;
    }

    private static double fade(double v) {
        return v * v * (3.0 - 2.0 * v);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
