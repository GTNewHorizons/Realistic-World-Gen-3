package com.irythill.rwg3.world.steps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;

public final class TerrainRegionsStep implements IGenerationStep {

    private static final double HILL_MINIMUM_HEIGHT = World.normalizedHeight(120.0);
    private static final double MOUNTAIN_MINIMUM_HEIGHT = World.normalizedHeight(300.0);
    private static final double PLAIN_MAXIMUM_HEIGHT = World.normalizedHeight(90.0);
    private static final int OCEAN_CLEARANCE = 5;

    @Override
    public void apply(long seed, World continent) {
        classifyTerrainRegions(continent);
        writeTerrainRegionFields(continent);
    }

    private static void classifyTerrainRegions(World state) {
        int size = state.height.length;
        state.terrainRegionIds = new int[size];
        state.terrainRegionKinds = new ArrayList<>();
        int[] oceanDistance = new int[size];
        Arrays.fill(oceanDistance, Integer.MAX_VALUE);
        Queue<Integer> frontier = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            if (state.oceanic[i] || state.height[i] <= World.SEA_LEVEL) {
                oceanDistance[i] = 0;
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int cell = frontier.remove();
            int nextDistance = oceanDistance[cell] + 1;
            for (int neighbor : state.index.neighbors[cell]) {
                if (neighbor != cell && nextDistance < oceanDistance[neighbor]) {
                    oceanDistance[neighbor] = nextDistance;
                    frontier.add(neighbor);
                }
            }
        }

        boolean[] mountain = new boolean[size];
        boolean[] hill = new boolean[size];
        boolean[] plain = new boolean[size];
        for (int i = 0; i < size; i++) {
            mountain[i] = !state.oceanic[i] && state.height[i] > MOUNTAIN_MINIMUM_HEIGHT;
            hill[i] = !state.oceanic[i] && state.height[i] > HILL_MINIMUM_HEIGHT && !mountain[i];
            plain[i] = !state.oceanic[i] && state.height[i] > World.SEA_LEVEL
                && state.height[i] <= PLAIN_MAXIMUM_HEIGHT
                && oceanDistance[i] >= OCEAN_CLEARANCE;
        }
        // Seed regions from distinct mountain, hill, and inland-plain components.
        collectTerrainRegions(state, mountain, TerrainRegionKind.MOUNTAIN);
        dilateMountainsToHillThreshold(state);
        for (int i = 0; i < hill.length; i++) hill[i] &= state.terrainRegionIds[i] == 0;
        collectTerrainRegions(state, hill, TerrainRegionKind.HILL);
        collectTerrainRegions(state, plain, TerrainRegionKind.PLAIN);
        dilateTerrainRegions(state);
        mergeAdjacentRegions(state);
    }

    private static void collectTerrainRegions(World state, boolean[] candidates, TerrainRegionKind kind) {
        boolean[] visited = new boolean[candidates.length];
        Queue<Integer> frontier = new ArrayDeque<>();
        for (int start = 0; start < candidates.length; start++) {
            if (!candidates[start] || visited[start]) continue;
            List<Integer> cells = new ArrayList<>();
            visited[start] = true;
            frontier.add(start);
            while (!frontier.isEmpty()) {
                int cell = frontier.remove();
                cells.add(cell);
                for (int neighbor : state.index.neighbors[cell]) {
                    if (neighbor != cell && candidates[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true;
                        frontier.add(neighbor);
                    }
                }
            }
            state.terrainRegionKinds.add(kind);
            int id = state.terrainRegionKinds.size();
            for (int cell : cells) state.terrainRegionIds[cell] = id;
        }
    }

    private static void dilateTerrainRegions(World state) {
        TerrainRegionKind[] order = { TerrainRegionKind.PLAIN, TerrainRegionKind.HILL, TerrainRegionKind.PLAIN,
            TerrainRegionKind.HILL, TerrainRegionKind.PLAIN, TerrainRegionKind.HILL, TerrainRegionKind.PLAIN,
            TerrainRegionKind.PLAIN, TerrainRegionKind.MOUNTAIN };
        boolean changed;
        do {
            changed = false;
            for (TerrainRegionKind kind : order) {
                int[] claims = new int[state.terrainRegionIds.length];
                for (int cell = 0; cell < claims.length; cell++) {
                    if (state.terrainRegionIds[cell] != 0 || state.height[cell] <= World.SEA_LEVEL) continue;
                    int selected = 0;
                    for (int neighbor : state.index.neighbors[cell]) {
                        int region = state.terrainRegionIds[neighbor];
                        if (region <= 0 || state.terrainRegionKinds.get(region - 1) != kind) continue;
                        if (selected == 0 || region < selected) selected = region;
                    }
                    claims[cell] = selected;
                }
                for (int cell = 0; cell < claims.length; cell++) {
                    if (claims[cell] == 0) continue;
                    state.terrainRegionIds[cell] = claims[cell];
                    changed = true;
                }
            }
        } while (changed);
    }

    private static void dilateMountainsToHillThreshold(World state) {
        boolean changed;
        do {
            changed = false;
            int[] claims = new int[state.terrainRegionIds.length];
            for (int cell = 0; cell < claims.length; cell++) {
                if (state.terrainRegionIds[cell] != 0 || state.height[cell] <= HILL_MINIMUM_HEIGHT) continue;
                int selected = 0;
                for (int neighbor : state.index.neighbors[cell]) {
                    int region = state.terrainRegionIds[neighbor];
                    if (region <= 0 || state.terrainRegionKinds.get(region - 1) != TerrainRegionKind.MOUNTAIN) continue;
                    if (selected == 0 || region < selected) selected = region;
                }
                claims[cell] = selected;
            }
            for (int cell = 0; cell < claims.length; cell++) {
                if (claims[cell] == 0) continue;
                state.terrainRegionIds[cell] = claims[cell];
                changed = true;
            }
        } while (changed);
    }

    private static void mergeAdjacentRegions(World state) {
        int[] parent = new int[state.terrainRegionKinds.size() + 1];
        for (int id = 1; id < parent.length; id++) parent[id] = id;
        for (int cell = 0; cell < state.terrainRegionIds.length; cell++) {
            int region = state.terrainRegionIds[cell];
            if (region == 0) continue;
            TerrainRegionKind kind = state.terrainRegionKinds.get(region - 1);
            for (int neighbor : state.index.neighbors[cell]) {
                int adjacent = state.terrainRegionIds[neighbor];
                if (adjacent == 0 || adjacent == region || state.terrainRegionKinds.get(adjacent - 1) != kind) continue;
                int first = root(parent, region);
                int second = root(parent, adjacent);
                parent[Math.max(first, second)] = Math.min(first, second);
            }
        }

        List<TerrainRegionKind> mergedKinds = new ArrayList<>();
        int[] mergedIds = new int[parent.length];
        for (int id = 1; id < parent.length; id++) {
            int representative = root(parent, id);
            if (representative == id) {
                mergedKinds.add(state.terrainRegionKinds.get(id - 1));
                mergedIds[id] = mergedKinds.size();
            } else {
                mergedIds[id] = mergedIds[representative];
            }
        }
        for (int cell = 0; cell < state.terrainRegionIds.length; cell++) {
            state.terrainRegionIds[cell] = mergedIds[state.terrainRegionIds[cell]];
        }
        state.terrainRegionKinds = mergedKinds;
    }

    private static int root(int[] parent, int id) {
        while (id != parent[id]) {
            parent[id] = parent[parent[id]];
            id = parent[id];
        }
        return id;
    }

    private static void writeTerrainRegionFields(World state) {
        for (int index = 0; index < state.index.coordinates.size(); index++) {
            Hex hex = state.grid.get(state.index.coordinates.get(index));
            hex.terrainRegionId = state.terrainRegionIds[index];
            hex.terrainRegionKind = hex.terrainRegionId > 0 ? state.terrainRegionKinds.get(hex.terrainRegionId - 1)
                : hex.height > World.SEA_LEVEL ? TerrainRegionKind.UNCLASSIFIED : null;
        }
    }

    public enum TerrainRegionKind {

        MOUNTAIN("Mountain"),
        HILL("Hill"),
        PLAIN("Plain"),
        UNCLASSIFIED("Unclassified terrain");

        public final String label;

        TerrainRegionKind(String label) {
            this.label = label;
        }
    }
}
