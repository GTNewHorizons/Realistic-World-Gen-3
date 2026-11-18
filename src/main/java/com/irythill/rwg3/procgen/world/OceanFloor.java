package com.irythill.rwg3.procgen.world;

import com.irythill.rwg3.procgen.GenerationStep;
import com.irythill.rwg3.procgen.continent.ContinentGenerator;
import com.irythill.rwg3.procgen.util.noise.ICoherentNoise.Query;
import com.irythill.rwg3.procgen.util.noise.PoissonNoise;
import com.irythill.rwg3.procgen.util.noise.Warped;

/** Infinite lightweight oceanic tectonic field without hydrology or erosion. */
final class OceanFloor {

    // Constants taken directly from the rust & python worldgen prototypes
    private static final double PLATE_SPACING = 18.7;
    private static final int POISSON_ROUNDS = 4;
    private static final double WARP_SCALE = 24.2;
    private static final double WARP_STRENGTH = 19.58;
    private static final double DRIFT = 3.0;
    private static final double MAXIMUM_SPIN = 8.0e-4;
    private static final double VELOCITY_NORMALIZER = DRIFT * 2.0 + MAXIMUM_SPIN * 120.0 + 1.0e-6;
    private static final double HEX_STEP = 2.0 / Math.sqrt(3.0);
    private static final double BASE_HEIGHT = 0.33;
    private static final double CONVERGENCE_HEIGHT = 1.4;
    private static final double DIVERGENCE_HEIGHT = 0.15;
    private static final int CONVERGENCE_RADIUS = 20;
    private static final int DIVERGENCE_RADIUS = 10;
    private static final double CONVERGENCE_DECAY = 0.82 * 0.5;
    private static final double DIVERGENCE_DECAY = 0.82;
    private static final double MAXIMUM_HEIGHT = 0.98;
    // Six applications of the finite solver's 0.7 center / 0.05 per-neighbor
    // filter, truncated to two hex rings and renormalized (94.37% of its mass).
    private static final int[] SMOOTH_Q = { 0, -1, -1, 0, 0, 1, 1, -2, -2, -2, -1, -1, 0, 0, 1, 1, 2, 2, 2 };
    private static final int[] SMOOTH_R = { 0, 0, 1, -1, 1, -1, 0, 0, 1, 2, -1, 2, -2, 2, -2, 1, -2, -1, 0 };
    private static final double[] SMOOTH_WEIGHT = { 0.197740681948123, 0.089565758904851, 0.089565758904851,
        0.089565758904851, 0.089565758904851, 0.089565758904851, 0.089565758904851, 0.016944885389075,
        0.027199242048053, 0.016944885389075, 0.027199242048053, 0.027199242048053, 0.016944885389075,
        0.016944885389075, 0.027199242048053, 0.027199242048053, 0.016944885389075, 0.027199242048053,
        0.016944885389075 };

    private final long seed;
    private final Warped<PoissonNoise> plates;
    private final ThreadLocal<Query> queries = ThreadLocal.withInitial(() -> new Query(5));
    private final ThreadLocal<RawHeightCache> rawHeights = ThreadLocal.withInitial(RawHeightCache::new);

    OceanFloor(long worldSeed) {
        seed = worldSeed ^ 0x4F4345414E464C52L;
        plates = new Warped<>(
            seed ^ 0x574152504F434541L,
            new PoissonNoise(seed ^ 0x504C41544553L, PLATE_SPACING, POISSON_ROUNDS),
            WARP_SCALE,
            WARP_STRENGTH);
    }

    Sample sample(double x, double y, GenerationStep stage) {
        Query query = queries.get();
        plates.nearest(x, y, 1, query);
        long owner = query.ids[0];
        if (stage instanceof ContinentGenerator.StepDefinition && ((ContinentGenerator.StepDefinition) stage).index < 3)
            return new Sample(BASE_HEIGHT, owner);

        double elevation = 0.0;
        double denominator = 2.0 * Math.sqrt(3.0) / 3.0;
        double fractionalQ = x * 0.5 + y / denominator;
        double fractionalR = x * 0.5 - y / denominator;
        int q = (int) Math.rint(fractionalQ);
        int r = (int) Math.rint(fractionalR);
        if (Math.abs(fractionalQ - q) < 1.0e-9 && Math.abs(fractionalR - r) < 1.0e-9) {
            RawHeightCache cache = rawHeights.get();
            for (int i = 0; i < SMOOTH_WEIGHT.length; i++) {
                elevation += cache.get(this, q + SMOOTH_Q[i], r + SMOOTH_R[i]) * SMOOTH_WEIGHT[i];
            }
        } else {
            for (int i = 0; i < SMOOTH_WEIGHT.length; i++) {
                double offsetX = SMOOTH_Q[i] + SMOOTH_R[i];
                double offsetY = (SMOOTH_Q[i] - SMOOTH_R[i]) * Math.sqrt(3.0) / 3.0;
                elevation += tectonicHeight(x + offsetX, y + offsetY) * SMOOTH_WEIGHT[i];
            }
        }
        return new Sample(clamp(elevation, WorldGenerator.MINIMUM_OCEAN_HEIGHT, MAXIMUM_HEIGHT), owner);
    }

    private double tectonicHeight(double x, double y) {
        Query query = queries.get();
        plates.nearest(x, y, 5, query);
        long owner = query.ids[0];
        double ownerAngle = driftAngle(owner);
        double ownerSpin = spin(owner);
        double ownerVelocityX = Math.cos(ownerAngle) * DRIFT - ownerSpin * (y - query.y[0]);
        double ownerVelocityY = Math.sin(ownerAngle) * DRIFT + ownerSpin * (x - query.x[0]);

        double convergence = 0.0;
        double divergence = 0.0;
        for (int neighbor = 1; neighbor < 5; neighbor++) {
            double normalX = query.x[neighbor] - query.x[0];
            double normalY = query.y[neighbor] - query.y[0];
            double normalLength = Math.hypot(normalX, normalY);
            if (normalLength <= 1.0e-9) continue;
            normalX /= normalLength;
            normalY /= normalLength;

            long neighborId = query.ids[neighbor];
            double neighborAngle = driftAngle(neighborId);
            double neighborSpin = spin(neighborId);
            double neighborVelocityX = Math.cos(neighborAngle) * DRIFT - neighborSpin * (y - query.y[neighbor]);
            double neighborVelocityY = Math.sin(neighborAngle) * DRIFT + neighborSpin * (x - query.x[neighbor]);
            double signed = ((neighborVelocityX - ownerVelocityX) * normalX
                + (neighborVelocityY - ownerVelocityY) * normalY) / VELOCITY_NORMALIZER;

            // Exact perpendicular distance to the two-site bisector in warped space.
            double firstSquared = query.distance[0] * query.distance[0];
            double neighborSquared = query.distance[neighbor] * query.distance[neighbor];
            double boundaryDistance = Math.max(0.0, (neighborSquared - firstSquared) / (2.0 * normalLength));
            double steps = boundaryDistance / HEX_STEP;
            double smoothedSteps = steps / 3.0;
            if (signed > 0.0 && steps <= DIVERGENCE_RADIUS) {
                divergence = Math.max(divergence, clamp(signed, 0.0, 1.0) * Math.pow(DIVERGENCE_DECAY, smoothedSteps));
            } else if (signed < 0.0 && steps <= CONVERGENCE_RADIUS) {
                convergence = Math
                    .max(convergence, clamp(-signed, 0.0, 1.0) * Math.pow(CONVERGENCE_DECAY, smoothedSteps));
            }
        }

        double elevation = BASE_HEIGHT;
        elevation += convergence * (CONVERGENCE_HEIGHT - elevation);
        elevation += divergence * (DIVERGENCE_HEIGHT - elevation);
        // The retained low-frequency term replaces the finite solver's random cell jitter.
        elevation += valueNoise(x / 18.0, y / 18.0) * 0.01;
        return elevation;
    }

    private double driftAngle(long id) {
        return unit(mix(seed ^ id ^ 0x4452494654L)) * Math.PI * 2.0;
    }

    private double spin(long id) {
        return signed(mix(seed ^ id ^ 0x5350494EL)) * MAXIMUM_SPIN;
    }

    private double valueNoise(double x, double y) {
        int x0 = floor(x), y0 = floor(y);
        double tx = smoothstep(x - x0), ty = smoothstep(y - y0);
        double a = signed(hash(x0, y0)), b = signed(hash(x0 + 1, y0));
        double c = signed(hash(x0, y0 + 1)), d = signed(hash(x0 + 1, y0 + 1));
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    private long hash(int x, int y) {
        return mix(seed ^ (long) x * 341873128712L ^ (long) y * 132897987541L);
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ value >>> 33;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static double signed(long value) {
        return unit(value) * 2.0 - 1.0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double smoothstep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double start, double end, double value) {
        return start + (end - start) * value;
    }

    private static int floor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    static final class Sample {

        final double elevation;
        final long plateId;

        Sample(double elevation, long plateId) {
            this.elevation = elevation;
            this.plateId = plateId;
        }
    }

    private static final class RawHeightCache {

        private static final int MASK = 2047;
        private final long[] keys = new long[MASK + 1];
        private final double[] values = new double[MASK + 1];
        private final boolean[] valid = new boolean[MASK + 1];

        double get(OceanFloor floor, int q, int r) {
            long key = (long) q & 0xffffffffL | (long) r << 32;
            int index = (int) mix(key) & MASK;
            if (valid[index] && keys[index] == key) return values[index];
            double x = q + r;
            double y = (q - r) * Math.sqrt(3.0) / 3.0;
            double value = floor.tectonicHeight(x, y);
            valid[index] = true;
            keys[index] = key;
            values[index] = value;
            return value;
        }
    }
}
