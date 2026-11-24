package com.irythill.rwg3.world.steps;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.Random;

import com.irythill.rwg3.world.World;

public final class BaseHeightStep implements IGenerationStep {

    private static final double FEATURE_DECAY_RATE = 0.82;
    private static final double SEA_FLOOR_HEIGHT = 0.33;
    private static final int SEA_SHORE_RADIUS = 30;
    private static final double SEA_DIVERGENCE_HEIGHT = 0.15;
    private static final int SEA_DIVERGENCE_RADIUS = 10;
    private static final double SEA_CONVERGENCE_HEIGHT = 1.4;
    private static final int SEA_CONVERGENCE_RADIUS = 20;
    private static final double CONTINENT_HEIGHT = 1.05;
    private static final int CONTINENT_SHORE_RADIUS = 30;
    private static final double CONTINENT_DIVERGENCE_HEIGHT = 1.05;
    private static final int CONTINENT_DIVERGENCE_RADIUS = 30;
    private static final double CONTINENT_CONVERGENCE_HEIGHT = 2.0;
    private static final int CONTINENT_CONVERGENCE_RADIUS = 30;

    @Override
    public void apply(long seed, World continent) {
        // Spread tectonic effects, shape shores, and smooth the resulting base terrain.
        generateHeights(seed, continent);
        // Publish the indexed height field back onto the previewable hex grid.
        writeElevations(continent);
    }

    private static void generateHeights(long seed, World state) {
        Random random = new Random(seed ^ 0x4241534548454947L);
        // Propagate each boundary signal using the radius and decay of its target crust type.
        double[] convSea = propagate(
            state.convergence,
            SEA_CONVERGENCE_RADIUS,
            FEATURE_DECAY_RATE * 0.5,
            state.index.neighbors);
        double[] divSea = propagate(state.divergence, SEA_DIVERGENCE_RADIUS, FEATURE_DECAY_RATE, state.index.neighbors);
        double[] convLand = propagate(
            state.convergence,
            CONTINENT_CONVERGENCE_RADIUS,
            FEATURE_DECAY_RATE,
            state.index.neighbors);
        double[] divLand = propagate(
            state.divergence,
            CONTINENT_DIVERGENCE_RADIUS,
            FEATURE_DECAY_RATE * 0.5,
            state.index.neighbors);
        int size = state.plateIds.length;
        int[] distance = new int[size];
        Arrays.fill(distance, Integer.MAX_VALUE / 2);
        Queue<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            for (int neighbor : state.index.neighbors[i]) {
                if (state.oceanic[i] != state.oceanic[neighbor]) {
                    distance[i] = 0;
                    queue.add(i);
                    break;
                }
            }
        }
        int maximumRadius = Math.max(SEA_SHORE_RADIUS, CONTINENT_SHORE_RADIUS);
        while (!queue.isEmpty()) {
            int i = queue.remove();
            if (distance[i] >= maximumRadius) continue;
            for (int neighbor : state.index.neighbors[i]) {
                if (distance[neighbor] > distance[i] + 1) {
                    distance[neighbor] = distance[i] + 1;
                    queue.add(neighbor);
                }
            }
        }
        for (int i = 0; i < size; i++) {
            int radius = state.oceanic[i] ? SEA_SHORE_RADIUS : CONTINENT_SHORE_RADIUS;
            double shore = Math.max(0.0, Math.min(1.0, 1.0 - (double) distance[i] / radius));
            double base = state.oceanic[i] ? SEA_FLOOR_HEIGHT : CONTINENT_HEIGHT;
            double shoreTarget = state.oceanic[i] ? World.SEA_LEVEL - 0.05 : World.SEA_LEVEL + 0.04;
            state.height[i] = base * (1.0 - shore) + shoreTarget * shore;
            double conv = state.oceanic[i] ? convSea[i] : convLand[i];
            double div = state.oceanic[i] ? divSea[i] : divLand[i];
            double convTarget = state.oceanic[i] ? SEA_CONVERGENCE_HEIGHT : CONTINENT_CONVERGENCE_HEIGHT;
            double divTarget = state.oceanic[i] ? SEA_DIVERGENCE_HEIGHT : CONTINENT_DIVERGENCE_HEIGHT;
            state.height[i] += conv * (convTarget - state.height[i]) + div * (divTarget - state.height[i]);
            state.height[i] += (random.nextDouble() - 0.5) * 0.03;
        }
        for (int pass = 0; pass < 6; pass++) {
            double[] smoothed = new double[size];
            for (int i = 0; i < size; i++) {
                double sum = 0.0;
                for (int neighbor : state.index.neighbors[i]) sum += state.height[neighbor];
                smoothed[i] = 0.7 * state.height[i] + 0.3 * sum / 6.0;
            }
            state.height = smoothed;
        }
    }

    private static double[] propagate(double[] local, int radius, double decay, int[][] neighbors) {
        double[] influence = local.clone();
        for (int pass = 0; pass < radius; pass++) {
            double[] next = influence.clone();
            for (int i = 0; i < influence.length; i++) {
                for (int neighbor : neighbors[i]) next[i] = Math.max(next[i], influence[neighbor] * decay);
            }
            influence = next;
        }
        return influence;
    }

    private static void writeElevations(World state) {
        for (int i = 0; i < state.height.length; i++) {
            state.grid.get(state.index.coordinates.get(i)).height = state.height[i];
        }
    }

}
