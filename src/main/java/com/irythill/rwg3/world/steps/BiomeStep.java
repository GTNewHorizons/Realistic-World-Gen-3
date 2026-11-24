package com.irythill.rwg3.world.steps;

import com.irythill.rwg3.world.Biome;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;

/** Calibrates smooth climate affinities toward fixed world-level biome shares. */
public final class BiomeStep implements IGenerationStep {

    private static final double TEMPERATURE_SCALE = 12.0;
    private static final double HUMIDITY_SCALE = 24.0;
    private static final double SOFTMAX_TEMPERATURE = 0.72;
    private static final int CALIBRATION_PASSES = 400;
    private static final double CALIBRATION_RATE = 0.18;

    @Override
    public void apply(long seed, World world) {
        Biome[] biomes = Biome.values();
        int landCount = countLand(world);
        world.dominantBiomes = new Biome[world.height.length];
        world.biomeShares = new double[biomes.length];
        if (landCount == 0) return;

        double[][] scores = rawScores(world, biomes);
        double[] bias = calibrate(world, scores, biomes, landCount);
        int[] counts = new int[biomes.length];
        for (int cell = 0; cell < world.height.length; cell++) {
            if (world.height[cell] <= World.SEA_LEVEL) continue;
            float[] weights = weights(scores[cell], bias);
            int dominant = dominant(scores[cell], bias);
            world.dominantBiomes[cell] = biomes[dominant];
            counts[dominant]++;
            Hex hex = world.grid.get(world.index.coordinates.get(cell));
            hex.biomeWeights = weights;
            hex.dominantBiome = biomes[dominant];
        }
        for (int biome = 0; biome < biomes.length; biome++)
            world.biomeShares[biome] = counts[biome] * 100.0 / landCount;
    }

    private static int countLand(World world) {
        int count = 0;
        for (double height : world.height) if (height > World.SEA_LEVEL) count++;
        return count;
    }

    private static double[][] rawScores(World world, Biome[] biomes) {
        double[][] scores = new double[world.height.length][];
        for (int cell = 0; cell < world.height.length; cell++) {
            if (world.height[cell] <= World.SEA_LEVEL) continue;
            scores[cell] = new double[biomes.length];
            for (int biome = 0; biome < biomes.length; biome++) {
                double temperature = (world.temperatureCelsius[cell] - biomes[biome].idealTemperature)
                    / TEMPERATURE_SCALE;
                double humidity = (world.humidityPercent[cell] - biomes[biome].idealHumidity) / HUMIDITY_SCALE;
                scores[cell][biome] = -(temperature * temperature + humidity * humidity);
            }
        }
        return scores;
    }

    private static double[] calibrate(World world, double[][] scores, Biome[] biomes, int landCount) {
        double[] bias = new double[biomes.length];
        int[] counts = new int[biomes.length];
        for (int pass = 0; pass < CALIBRATION_PASSES; pass++) {
            for (int biome = 0; biome < counts.length; biome++) counts[biome] = 0;
            for (int cell = 0; cell < world.height.length; cell++) {
                if (scores[cell] != null) counts[dominant(scores[cell], bias)]++;
            }
            double maximumError = 0.0;
            for (int biome = 0; biome < biomes.length; biome++) {
                double target = biomes[biome].targetPercent / 100.0;
                double actual = counts[biome] / (double) landCount;
                maximumError = Math.max(maximumError, Math.abs(target - actual));
                bias[biome] += CALIBRATION_RATE * Math.log(target / Math.max(1.0 / landCount, actual));
            }
            center(bias);
            if (maximumError < 0.0025) break;
        }
        return bias;
    }

    private static int dominant(double[] scores, double[] bias) {
        int selected = 0;
        double best = scores[0] + bias[0];
        for (int biome = 1; biome < scores.length; biome++) {
            double candidate = scores[biome] + bias[biome];
            if (candidate > best) {
                selected = biome;
                best = candidate;
            }
        }
        return selected;
    }

    private static float[] weights(double[] scores, double[] bias) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (int biome = 0; biome < scores.length; biome++)
            maximum = Math.max(maximum, (scores[biome] + bias[biome]) / SOFTMAX_TEMPERATURE);
        double total = 0.0;
        double[] values = new double[scores.length];
        for (int biome = 0; biome < scores.length; biome++) {
            values[biome] = Math.exp((scores[biome] + bias[biome]) / SOFTMAX_TEMPERATURE - maximum);
            total += values[biome];
        }
        float[] result = new float[scores.length];
        for (int biome = 0; biome < scores.length; biome++) result[biome] = (float) (values[biome] / total * 100.0);
        return result;
    }

    private static void center(double[] values) {
        double mean = 0.0;
        for (double value : values) mean += value;
        mean /= values.length;
        for (int index = 0; index < values.length; index++) values[index] -= mean;
    }
}
