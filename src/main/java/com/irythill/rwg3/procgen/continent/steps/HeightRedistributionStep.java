package com.irythill.rwg3.procgen.continent.steps;

import java.util.Arrays;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.GenerationSettings;

public final class HeightRedistributionStep implements IContinentStep {

    @Override
    public void apply(long seed, Continent continent) {
        // Remap ranked land heights into the configured plains, hills, mountains, and peaks bands.
        redistributeLandHeights(continent);
        // Publish the redistributed height field back onto the previewable hex grid.
        writeElevations(continent);
    }

    private static void redistributeLandHeights(Continent state) {
        GenerationSettings config = state.config;
        int landCount = 0;
        for (double height : state.height) if (height > config.seaLevel) landCount++;
        if (landCount < 2) return;

        double[] distribution = new double[landCount];
        int cursor = 0;
        for (double height : state.height) if (height > config.seaLevel) distribution[cursor++] = height;
        Arrays.sort(distribution);

        double plainsEnd = config.plainsFraction;
        double hillsEnd = plainsEnd + config.hillsFraction;
        double mountainsEnd = hillsEnd + config.mountainsFraction;
        if (plainsEnd <= 0.0 || hillsEnd <= plainsEnd || mountainsEnd <= hillsEnd || mountainsEnd >= 1.0) {
            throw new IllegalArgumentException(
                "Terrain fractions must leave positive plains, hills, mountains and peaks");
        }
        if (config.heightRemappingStrength < 0.0 || config.heightRemappingStrength > 1.0) {
            throw new IllegalArgumentException("Height remapping strength must be between zero and one");
        }
        if (config.heightRemappingCurveSmoothing < 0.0 || config.heightRemappingCurveSmoothing > 1.0) {
            throw new IllegalArgumentException("Height remapping curve smoothing must be between zero and one");
        }

        // Binary-search each height's rank, then blend it toward its configured terrain band.
        for (int i = 0; i < state.height.length; i++) {
            double height = state.height[i];
            if (height <= config.seaLevel) continue;
            int first = lowerBound(distribution, height);
            int last = upperBound(distribution, height);
            double percentile = ((first + last - 1) * 0.5) / (distribution.length - 1.0);
            double remapped;
            if (percentile < plainsEnd) {
                remapped = remapBand(
                    percentile / plainsEnd,
                    config.landMinimumHeight,
                    config.plainsMaximumHeight,
                    config.heightRemappingCurveSmoothing);
            } else if (percentile < hillsEnd) {
                remapped = remapBand(
                    (percentile - plainsEnd) / (hillsEnd - plainsEnd),
                    config.plainsMaximumHeight,
                    config.hillsMaximumHeight,
                    config.heightRemappingCurveSmoothing);
            } else if (percentile < mountainsEnd) {
                remapped = remapBand(
                    (percentile - hillsEnd) / (mountainsEnd - hillsEnd),
                    config.hillsMaximumHeight,
                    config.mountainsMaximumHeight,
                    config.heightRemappingCurveSmoothing);
            } else {
                remapped = remapBand(
                    (percentile - mountainsEnd) / (1.0 - mountainsEnd),
                    config.mountainsMaximumHeight,
                    config.peaksMaximumHeight,
                    config.heightRemappingCurveSmoothing);
            }
            state.height[i] = height + (remapped - height) * config.heightRemappingStrength;
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

    private static void writeElevations(Continent state) {
        for (int i = 0; i < state.height.length; i++) {
            state.grid.get(state.index.coordinates.get(i)).height = state.height[i];
        }
    }
}
