package com.irythill.rwg3.procgen.continent.steps;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.GenerationSettings;

public final class BaseHeightStep implements IContinentStep {

    @Override
    public void apply(long seed, Continent continent) {
        // Spread tectonic effects, shape shores, and smooth the resulting base terrain.
        generateHeights(continent);
        // Publish the indexed height field back onto the previewable hex grid.
        writeElevations(continent);
    }

    private static void generateHeights(Continent state) {
        GenerationSettings p = state.config;
        // Propagate each boundary signal using the radius and decay of its target crust type.
        double[] convSea = propagate(
            state.convergence,
            p.seaConvergenceDetectionRadius,
            p.featureDecayRate * p.seaConvergenceDecayFactor,
            state.index.terrainNeighbors);
        double[] divSea = propagate(
            state.divergence,
            p.seaDivergenceDetectionRadius,
            p.featureDecayRate * p.seaDivergenceDecayFactor,
            state.index.terrainNeighbors);
        double[] convLand = propagate(
            state.convergence,
            p.continentConvergenceDetectionRadius,
            p.featureDecayRate * p.continentConvergenceDecayFactor,
            state.index.terrainNeighbors);
        double[] divLand = propagate(
            state.divergence,
            p.continentDivergenceDetectionRadius,
            p.featureDecayRate * p.continentDivergenceDecayFactor,
            state.index.terrainNeighbors);
        int size = state.plateIds.length;
        int[] distance = new int[size];
        Arrays.fill(distance, Integer.MAX_VALUE / 2);
        Queue<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            for (int neighbor : state.index.terrainNeighbors[i]) {
                if (state.oceanic[i] != state.oceanic[neighbor]) {
                    distance[i] = 0;
                    queue.add(i);
                    break;
                }
            }
        }
        int maximumRadius = Math.max(p.seaShoreDetectionRadius, p.continentShoreDetectionRadius);
        while (!queue.isEmpty()) {
            int i = queue.remove();
            if (distance[i] >= maximumRadius) continue;
            for (int neighbor : state.index.terrainNeighbors[i]) {
                if (distance[neighbor] > distance[i] + 1) {
                    distance[neighbor] = distance[i] + 1;
                    queue.add(neighbor);
                }
            }
        }
        for (int i = 0; i < size; i++) {
            int radius = state.oceanic[i] ? p.seaShoreDetectionRadius : p.continentShoreDetectionRadius;
            double shore = Math.max(0.0, Math.min(1.0, 1.0 - (double) distance[i] / radius));
            double base = state.oceanic[i] ? p.seaFloorHeight : p.continentHeight;
            double shoreTarget = state.oceanic[i] ? p.seaLevel - 0.05 : p.seaLevel + 0.04;
            state.height[i] = base * (1.0 - shore) + shoreTarget * shore;
            double conv = state.oceanic[i] ? convSea[i] : convLand[i];
            double div = state.oceanic[i] ? divSea[i] : divLand[i];
            double convTarget = state.oceanic[i] ? p.seaConvergenceHeight : p.continentConvergenceHeight;
            double divTarget = state.oceanic[i] ? p.seaDivergenceHeight : p.continentDivergenceHeight;
            state.height[i] += conv * (convTarget - state.height[i]) + div * (divTarget - state.height[i]);
            state.height[i] += (state.random.nextDouble() - 0.5) * 0.03;
        }
        for (int pass = 0; pass < 6; pass++) {
            double[] smoothed = new double[size];
            for (int i = 0; i < size; i++) {
                double sum = 0.0;
                for (int neighbor : state.index.terrainNeighbors[i]) sum += state.height[neighbor];
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

    private static void writeElevations(Continent state) {
        for (int i = 0; i < state.height.length; i++) {
            state.grid.get(state.index.coordinates.get(i)).height = state.height[i];
        }
    }

}
