package com.irythill.rwg3.procgen.continent.steps;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.util.hex.GridIndex;
import com.irythill.rwg3.procgen.util.hex.Vec2;

public final class PlateMotionStep implements IContinentStep {

    @Override
    public void apply(long seed, Continent continent) {
        // Build dense indexed arrays and evaluate each plate's velocity at every cell.
        prepareIndexedFields(continent);
        // Convert relative velocity across plate boundaries into convergence and divergence.
        computeBoundaryFields(continent);
        // Publish the indexed motion fields back onto the previewable hex grid.
        writeMotionFields(continent);
    }

    private static void prepareIndexedFields(Continent state) {
        state.index = new GridIndex(state.grid);
        int size = state.index.coordinates.size();
        state.plateIds = new int[size];
        state.oceanic = new boolean[size];
        state.velocity = new Vec2[size];
        state.convergence = new double[size];
        state.divergence = new double[size];
        state.height = new double[size];
        for (int i = 0; i < size; i++) {
            int id = state.grid.get(state.index.coordinates.get(i)).plateId;
            state.plateIds[i] = id;
            state.oceanic[i] = id > 0 && state.plates.get(id - 1).oceanic;
            state.velocity[i] = id == 0 ? Vec2.ZERO
                : state.plates.get(id - 1)
                    .velocityAt(state, state.index.worldPositions[i]);
        }
    }

    private static void computeBoundaryFields(Continent state) {
        double maxSpeed = 2.0 * state.config.driftMultiplier
            + state.config.spinMultiplier * Math.max(state.grid.width(), state.grid.height())
            + 1.0e-6;
        double[] edges = new double[state.plateIds.length];
        for (int direction = 0; direction < 6; direction++) {
            for (int i = 0; i < state.plateIds.length; i++) {
                int neighbor = state.index.terrainNeighbors[i][direction];
                if (neighbor == i) continue;
                if (state.plateIds[neighbor] == state.plateIds[i]) continue;
                double signed = state.velocity[neighbor].subtract(state.velocity[i])
                    .dot(state.index.directionVectors[direction]);
                if (signed > 0.0) state.divergence[i] += signed;
                else state.convergence[i] -= signed;
                edges[i]++;
            }
        }
        for (int i = 0; i < state.plateIds.length; i++) {
            if (edges[i] > 0.0) {
                state.convergence[i] = Math.max(0.0, Math.min(1.0, state.convergence[i] / edges[i] / maxSpeed));
                state.divergence[i] = Math.max(0.0, Math.min(1.0, state.divergence[i] / edges[i] / maxSpeed));
            }
        }
    }

    private static void writeMotionFields(Continent state) {
        for (int index = 0; index < state.index.coordinates.size(); index++) {
            Hex hex = state.grid.get(state.index.coordinates.get(index));
            hex.oceanic = state.oceanic[index];
            hex.velocity = state.velocity[index];
            hex.convergence = state.convergence[index];
            hex.divergence = state.divergence[index];
        }
    }

}
