package com.irythill.rwg3.world.steps;

import java.util.Arrays;

import com.irythill.rwg3.world.World;

public final class HeightRedistributionStep implements IGenerationStep {

    private static final double PLAINS_FRACTION = 0.60;
    private static final double HILLS_FRACTION = 0.25;
    private static final double MOUNTAINS_FRACTION = 0.12;
    private static final double REMAPPING_STRENGTH = 0.75;
    private static final double CURVE_SMOOTHING = 0.50;
    private static final double LAND_MINIMUM_HEIGHT = World.normalizedHeight(65.0);
    private static final double PLAINS_MAXIMUM_HEIGHT = World.normalizedHeight(75.0);
    private static final double HILLS_MAXIMUM_HEIGHT = World.normalizedHeight(150.0);
    private static final double MOUNTAINS_MAXIMUM_HEIGHT = World.normalizedHeight(600.0);

    @Override
    public void apply(long seed, World continent) {
        // Remap ranked land heights into the configured plains, hills, mountains, and peaks bands.
        redistributeLandHeights(continent);
        // Publish the redistributed height field back onto the previewable hex grid.
        writeElevations(continent);
    }

    private static void redistributeLandHeights(World state) {
        int landCount = 0;
        for (double height : state.height) if (height > World.SEA_LEVEL) landCount++;
        if (landCount < 2) return;

        double[] distribution = new double[landCount];
        int cursor = 0;
        for (double height : state.height) if (height > World.SEA_LEVEL) distribution[cursor++] = height;
        Arrays.sort(distribution);

        double plainsEnd = PLAINS_FRACTION;
        double hillsEnd = plainsEnd + HILLS_FRACTION;
        double mountainsEnd = hillsEnd + MOUNTAINS_FRACTION;

        // Binary-search each height's rank, then blend it toward its configured terrain band.
        for (int i = 0; i < state.height.length; i++) {
            double height = state.height[i];
            if (height <= World.SEA_LEVEL) continue;
            int first = lowerBound(distribution, height);
            int last = upperBound(distribution, height);
            double percentile = ((first + last - 1) * 0.5) / (distribution.length - 1.0);
            double remapped;
            if (percentile < plainsEnd) {
                remapped = remapBand(
                    percentile / plainsEnd,
                    LAND_MINIMUM_HEIGHT,
                    PLAINS_MAXIMUM_HEIGHT,
                    CURVE_SMOOTHING);
            } else if (percentile < hillsEnd) {
                remapped = remapBand(
                    (percentile - plainsEnd) / (hillsEnd - plainsEnd),
                    PLAINS_MAXIMUM_HEIGHT,
                    HILLS_MAXIMUM_HEIGHT,
                    CURVE_SMOOTHING);
            } else if (percentile < mountainsEnd) {
                remapped = remapBand(
                    (percentile - hillsEnd) / (mountainsEnd - hillsEnd),
                    HILLS_MAXIMUM_HEIGHT,
                    MOUNTAINS_MAXIMUM_HEIGHT,
                    CURVE_SMOOTHING);
            } else {
                remapped = remapBand(
                    (percentile - mountainsEnd) / (1.0 - mountainsEnd),
                    MOUNTAINS_MAXIMUM_HEIGHT,
                    World.MAXIMUM_HEIGHT,
                    CURVE_SMOOTHING);
            }
            state.height[i] = height + (remapped - height) * REMAPPING_STRENGTH;
        }
    }

    private static double remapBand(double value, double minimum, double maximum, double curveSmoothing) {
        double smooth = value * value * (3.0 - 2.0 * value);
        double curved = value + (smooth - value) * curveSmoothing;
        return minimum + (maximum - minimum) * curved;
    }

    private static int lowerBound(double[] sorted, double value) {
        int low = 0, high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] < value) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static int upperBound(double[] sorted, double value) {
        int low = 0, high = sorted.length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (sorted[middle] <= value) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private static void writeElevations(World state) {
        for (int i = 0; i < state.height.length; i++) {
            state.grid.get(state.index.coordinates.get(i)).height = state.height[i];
        }
    }
}
