package com.irythill.rwg3.procgen.util;

/** Height-modulated procedural erosion for land terrain. */
public final class TerrainErosion {

    private static final double TAU = Math.PI * 2.0;
    private static final double RIDGE_ROUNDING = 0.1;
    private static final double CREASE_ROUNDING = 0.0;
    private static final double ROUNDING_INPUT_MULTIPLIER = 0.1;
    private static final double ROUNDING_OCTAVE_MULTIPLIER = 2.0;
    private static final double ONSET_INPUT = 1.25;
    private static final double ONSET_OCTAVE = 1.25;
    private static final double CELL_SCALE = 0.7;
    private static final double NORMALIZATION = 0.5;
    private static final double EROSION_SCALE = 2.7;
    private static final double EROSION_STRENGTH = 0.09333333333333334;
    private static final double EROSION_GULLY_WEIGHT = 0.5;
    private static final double EROSION_DETAIL = 1.5;
    private static final int EROSION_MAXIMUM_OCTAVES = 5;
    private static final double EROSION_LACUNARITY = 2.0;
    private static final double EROSION_GAIN = 0.5;
    private static final double EROSION_ASSUMED_SLOPE = 0.7;
    private static final double EROSION_SLOPE_OVERRIDE = 1.0;

    private final long seed;
    private final ThreadLocal<PhacelleSample> samples = ThreadLocal.withInitial(PhacelleSample::new);

    public TerrainErosion(long seed) {
        this.seed = seed ^ 0xA54FF53A5F1D36F1L;
    }

    public double apply(double x, double y, double height, double strength, double sourceSlopeX, double sourceSlopeY) {
        strength = clamp01(strength);
        if (strength <= 0.0) return height;
        if (Math.hypot(sourceSlopeX, sourceSlopeY) <= 1.0e-10) {
            double angle = directionNoise((x + 23.24375) / 87.5, (y - 13.00625) / 87.5) * TAU;
            sourceSlopeX = Math.cos(angle) * EROSION_ASSUMED_SLOPE;
            sourceSlopeY = Math.sin(angle) * EROSION_ASSUMED_SLOPE;
        }
        double delta = erosionDelta(x, y, sourceSlopeX, sourceSlopeY, 0.0);
        return height + delta * strength;
    }

    private double erosionDelta(double x, double y, double slopeX, double slopeY, double fadeTarget) {
        double strength = EROSION_STRENGTH * EROSION_SCALE;
        double frequency = 1.0 / (EROSION_SCALE * CELL_SCALE);
        double slopeLength = Math.max(Math.hypot(slopeX, slopeY), 1.0e-10);
        double roundingForInput = lerp(CREASE_ROUNDING, RIDGE_ROUNDING, clamp01(fadeTarget + 0.5))
            * ROUNDING_INPUT_MULTIPLIER;
        double combinedMask = easeOut(smoothStart(slopeLength * ONSET_INPUT, roundingForInput * ONSET_INPUT));
        double gullyX = lerp(slopeX, slopeX / slopeLength * EROSION_ASSUMED_SLOPE, EROSION_SLOPE_OVERRIDE);
        double gullyY = lerp(slopeY, slopeY / slopeLength * EROSION_ASSUMED_SLOPE, EROSION_SLOPE_OVERRIDE);
        double heightDelta = 0.0;
        double roundingMultiplier = 1.0;
        PhacelleSample sample = samples.get();

        for (int octave = 0; octave < EROSION_MAXIMUM_OCTAVES; octave++) {
            phacelle(x * frequency, y * frequency, gullyX, gullyY, frequency, sample);
            double octaveStrength = strength;
            double sloping = Math.abs(sample.sin);
            double derivativeScale = -frequency;
            double derivativeX = sample.sideX * derivativeScale;
            double derivativeY = sample.sideY * derivativeScale;
            double sign = Math.signum(sample.sin);
            gullyX += sign * derivativeX * octaveStrength * EROSION_GULLY_WEIGHT;
            gullyY += sign * derivativeY * octaveStrength * EROSION_GULLY_WEIGHT;

            double fadedHeight = lerp(fadeTarget, sample.cos * EROSION_GULLY_WEIGHT, combinedMask);
            heightDelta += fadedHeight * octaveStrength;
            fadeTarget = fadedHeight;
            double rounding = lerp(CREASE_ROUNDING, RIDGE_ROUNDING, clamp01(sample.cos + 0.5)) * roundingMultiplier;
            double newMask = easeOut(smoothStart(sloping * ONSET_OCTAVE, rounding * ONSET_OCTAVE));
            combinedMask = powInverse(combinedMask, EROSION_DETAIL) * newMask;
            strength *= EROSION_GAIN;
            frequency *= EROSION_LACUNARITY;
            roundingMultiplier *= ROUNDING_OCTAVE_MULTIPLIER;
        }
        return heightDelta;
    }

    private void phacelle(double x, double y, double directionX, double directionY, double frequency,
        PhacelleSample output) {
        double directionLength = Math.hypot(directionX, directionY);
        if (directionLength > 1.0e-10) {
            directionX /= directionLength;
            directionY /= directionLength;
        }
        double sideX = -directionY * CELL_SCALE * TAU;
        double sideY = directionX * CELL_SCALE * TAU;
        int baseX = fastFloor(x);
        int baseY = fastFloor(y);
        double fractionX = x - baseX;
        double fractionY = y - baseY;
        double phaseCos = 0.0;
        double phaseSin = 0.0;
        double weightSum = 0.0;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 2; dx++) {
                long hash = mix64(
                    seed ^ (long) (baseX + dx) * 0x9E3779B97F4A7C15L ^ (long) (baseY + dy) * 0xC2B2AE3D27D4EB4FL);
                double randomX = unit(hash) - 0.5;
                double randomY = unit(mix64(hash)) - 0.5;
                double fromX = fractionX - dx - randomX;
                double fromY = fractionY - dy - randomY;
                double weight = Math.max(0.0, Math.exp(-(fromX * fromX + fromY * fromY) * 2.0) - 0.01111);
                if (weight == 0.0) continue;
                double wave = fromX * sideX + fromY * sideY + TAU * 0.25;
                phaseCos += Math.cos(wave) * weight;
                phaseSin += Math.sin(wave) * weight;
                weightSum += weight;
            }
        }
        if (weightSum <= 1.0e-12) {
            output.cos = 0.0;
            output.sin = 0.0;
        } else {
            double interpolatedCos = phaseCos / weightSum;
            double interpolatedSin = phaseSin / weightSum;
            double magnitude = Math.max(1.0 - NORMALIZATION, Math.hypot(interpolatedCos, interpolatedSin));
            output.cos = interpolatedCos / magnitude;
            output.sin = interpolatedSin / magnitude;
        }
        output.sideX = sideX;
        output.sideY = sideY;
    }

    private double directionNoise(double x, double y) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        double tx = smooth(x - x0);
        double ty = smooth(y - y0);
        double a = signedNoise(x0, y0);
        double b = signedNoise(x0 + 1, y0);
        double c = signedNoise(x0, y0 + 1);
        double d = signedNoise(x0 + 1, y0 + 1);
        return lerp(lerp(a, b, tx), lerp(c, d, tx), ty);
    }

    private double signedNoise(int x, int y) {
        long hash = mix64(seed ^ (long) x * 341873128712L ^ (long) y * 132897987541L ^ 0x444952454354L);
        return unit(hash) * 2.0 - 1.0;
    }

    private static double powInverse(double value, double power) {
        return 1.0 - Math.pow(1.0 - clamp01(value), power);
    }

    private static double easeOut(double value) {
        value = 1.0 - clamp01(value);
        return 1.0 - value * value;
    }

    private static double smoothStart(double value, double smoothing) {
        if (smoothing <= 0.0 || value >= smoothing) return value - 0.5 * smoothing;
        return 0.5 * value * value / smoothing;
    }

    private static double smooth(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double first, double second, double amount) {
        return first + (second - first) * amount;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int fastFloor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double unit(long value) {
        return (value >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static final class PhacelleSample {

        double cos;
        double sin;
        double sideX;
        double sideY;
    }
}
