package com.irythill.rwg3.procgen.continent.steps;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import com.irythill.rwg3.procgen.continent.Continent;
import com.irythill.rwg3.procgen.continent.Hex;
import com.irythill.rwg3.procgen.util.hex.Vec2;

public final class TerrainRegionsStep implements IContinentStep {

    @Override
    public void apply(long seed, Continent continent) {
        // Discover, name, expand, and weight the semantic terrain regions.
        classifyTerrainRegions(continent);
        // Publish region identity and erosion weight back onto the previewable hex grid.
        writeTerrainRegionFields(continent);
    }

    private static void classifyTerrainRegions(Continent state) {
        int size = state.height.length;
        state.terrainRegionIds = new int[size];
        state.terrainRegions = new ArrayList<>();
        state.terrainRegionKinds = new ArrayList<>();
        double continentCenterX = 0.0, continentCenterY = 0.0;
        int continentCellCount = 0;
        for (int i = 0; i < size; i++) {
            if (state.oceanic[i] || state.height[i] <= state.config.seaLevel) continue;
            continentCenterX += state.index.worldPositions[i].x;
            continentCenterY += state.index.worldPositions[i].y;
            continentCellCount++;
        }
        if (continentCellCount > 0) {
            continentCenterX /= continentCellCount;
            continentCenterY /= continentCellCount;
        } else {
            continentCenterX = state.grid.width() * 0.5;
            continentCenterY = state.grid.height() * 0.5;
        }
        int[] oceanDistance = new int[size];
        Arrays.fill(oceanDistance, Integer.MAX_VALUE);
        Queue<Integer> frontier = new ArrayDeque<>();
        for (int i = 0; i < size; i++) {
            if (state.oceanic[i] || state.height[i] <= state.config.seaLevel) {
                oceanDistance[i] = 0;
                frontier.add(i);
            }
        }
        while (!frontier.isEmpty()) {
            int cell = frontier.remove();
            int nextDistance = oceanDistance[cell] + 1;
            for (int neighbor : state.index.terrainNeighbors[cell]) {
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
            mountain[i] = !state.oceanic[i] && state.height[i] > state.config.terrainRegionMountainMinimumHeight;
            hill[i] = !state.oceanic[i] && state.height[i] > state.config.terrainRegionHillMinimumHeight
                && !mountain[i];
            plain[i] = !state.oceanic[i] && state.height[i] > state.config.seaLevel
                && state.height[i] <= state.config.terrainRegionPlainMaximumHeight
                && oceanDistance[i] >= state.config.terrainRegionOceanClearance;
        }
        // Seed named regions from distinct mountain, hill, and inland-plain components.
        collectTerrainRegions(state, mountain, TerrainRegionKind.MOUNTAIN, continentCenterX, continentCenterY);
        dilateMountainsToHillThreshold(state);
        for (int i = 0; i < hill.length; i++) hill[i] &= state.terrainRegionIds[i] == 0;
        collectTerrainRegions(state, hill, TerrainRegionKind.HILL, continentCenterX, continentCenterY);
        collectTerrainRegions(state, plain, TerrainRegionKind.PLAIN, continentCenterX, continentCenterY);
        // Fill remaining land, separate islands, stabilize duplicate names, and blend presets.
        dilateTerrainRegions(state);
        addIslandRegions(state, continentCenterX, continentCenterY);
        numberDuplicateRegionNames(state);
        computeTerrainPresetWeights(state);
    }

    private static void collectTerrainRegions(Continent state, boolean[] candidates, TerrainRegionKind kind,
        double continentCenterX, double continentCenterY) {
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
                for (int neighbor : state.index.terrainNeighbors[cell]) {
                    if (neighbor != cell && candidates[neighbor] && !visited[neighbor]) {
                        visited[neighbor] = true;
                        frontier.add(neighbor);
                    }
                }
            }
            addTerrainRegion(state, cells, kind, continentCenterX, continentCenterY);
        }
    }

    private static void addTerrainRegion(Continent state, List<Integer> cells, TerrainRegionKind kind,
        double continentCenterX, double continentCenterY) {
        double centerX = 0.0, centerY = 0.0, maximum = Double.NEGATIVE_INFINITY;
        for (int cell : cells) {
            Vec2 point = state.index.worldPositions[cell];
            centerX += point.x;
            centerY += point.y;
            maximum = Math.max(maximum, state.height[cell]);
        }
        centerX /= cells.size();
        centerY /= cells.size();
        double xx = 0.0, yy = 0.0, xy = 0.0;
        for (int cell : cells) {
            Vec2 point = state.index.worldPositions[cell];
            double dx = point.x - centerX, dy = point.y - centerY;
            xx += dx * dx;
            yy += dy * dy;
            xy += dx * dy;
        }
        double trace = xx + yy;
        double discriminant = Math.sqrt(Math.max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy));
        double major = (trace + discriminant) * 0.5;
        double minor = Math.max(1.0e-9, (trace - discriminant) * 0.5);
        boolean linear = cells.size() >= 4 && major / minor >= 4.0;
        String feature;
        if (kind == TerrainRegionKind.PLAIN) feature = "Plains";
        else if (kind == TerrainRegionKind.MOUNTAIN)
            feature = linear ? "Mountain Chain" : cells.size() >= 7 ? "Mountains" : "Peak";
        else feature = linear ? "Hill Chain" : cells.size() == 1 ? "Hill" : "Hills";
        String direction = relativeDirection(
            centerX - continentCenterX,
            centerY - continentCenterY,
            state.grid.width() * 0.1);
        Vec2 anchor = nearestRegionCell(state, cells, centerX, centerY);
        int id = state.terrainRegions.size() + 1;
        for (int cell : cells) state.terrainRegionIds[cell] = id;
        state.terrainRegionKinds.add(kind);
        state.terrainRegions.add(
            new TerrainRegion(
                id,
                direction + " " + feature,
                anchor.x,
                anchor.y,
                state.config.blockHeight(maximum),
                cells.size(),
                kind == TerrainRegionKind.MOUNTAIN));
    }

    private static void dilateTerrainRegions(Continent state) {
        TerrainRegionKind[] order = { TerrainRegionKind.PLAIN, TerrainRegionKind.HILL, TerrainRegionKind.PLAIN,
            TerrainRegionKind.HILL, TerrainRegionKind.PLAIN, TerrainRegionKind.HILL, TerrainRegionKind.MOUNTAIN };
        boolean changed;
        do {
            changed = false;
            for (TerrainRegionKind kind : order) {
                int[] claims = new int[state.terrainRegionIds.length];
                for (int cell = 0; cell < claims.length; cell++) {
                    if (state.terrainRegionIds[cell] != 0 || state.height[cell] <= state.config.seaLevel) continue;
                    int selected = 0;
                    for (int neighbor : state.index.terrainNeighbors[cell]) {
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

    private static void addIslandRegions(Continent state, double continentCenterX, double continentCenterY) {
        boolean[] visited = new boolean[state.height.length];
        List<List<Integer>> islands = new ArrayList<>();
        Queue<Integer> frontier = new ArrayDeque<>();
        for (int start = 0; start < visited.length; start++) {
            if (visited[start] || state.height[start] <= state.config.seaLevel) continue;
            List<Integer> cells = new ArrayList<>();
            visited[start] = true;
            frontier.add(start);
            while (!frontier.isEmpty()) {
                int cell = frontier.remove();
                cells.add(cell);
                for (int neighbor : state.index.terrainNeighbors[cell]) {
                    if (neighbor != cell && !visited[neighbor] && state.height[neighbor] > state.config.seaLevel) {
                        visited[neighbor] = true;
                        frontier.add(neighbor);
                    }
                }
            }
            islands.add(cells);
        }
        int continent = -1;
        int continentPlateCells = -1;
        for (int i = 0; i < islands.size(); i++) {
            int continental = 0;
            for (int cell : islands.get(i)) if (!state.oceanic[cell]) continental++;
            if (continental > continentPlateCells
                || continental == continentPlateCells && (continent < 0 || islands.get(i)
                    .size()
                    > islands.get(continent)
                        .size())) {
                continent = i;
                continentPlateCells = continental;
            }
        }
        for (int i = 0; i < islands.size(); i++) {
            if (i == continent) continue;
            List<Integer> cells = islands.get(i);
            double centerX = 0.0, centerY = 0.0, maximum = Double.NEGATIVE_INFINITY;
            for (int cell : cells) {
                centerX += state.index.worldPositions[cell].x;
                centerY += state.index.worldPositions[cell].y;
                maximum = Math.max(maximum, state.height[cell]);
            }
            centerX /= cells.size();
            centerY /= cells.size();
            String direction = islandDirection(centerX - continentCenterX, centerY - continentCenterY);
            Vec2 anchor = nearestRegionCell(state, cells, centerX, centerY);
            int id = state.terrainRegions.size() + 1;
            for (int cell : cells) state.terrainRegionIds[cell] = id;
            state.terrainRegions.add(
                new TerrainRegion(
                    id,
                    direction + " Island",
                    anchor.x,
                    anchor.y,
                    state.config.blockHeight(maximum),
                    cells.size(),
                    false));
        }
    }

    private static String islandDirection(double dx, double dy) {
        String[] directions = { "East", "North-East", "North", "North-West", "West", "South-West", "South",
            "South-East" };
        int sector = Math.floorMod((int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4.0)), directions.length);
        return directions[sector];
    }

    private static Vec2 nearestRegionCell(Continent state, List<Integer> cells, double centerX, double centerY) {
        Vec2 selected = state.index.worldPositions[cells.get(0)];
        double best = Double.POSITIVE_INFINITY;
        for (int cell : cells) {
            Vec2 point = state.index.worldPositions[cell];
            double dx = point.x - centerX, dy = point.y - centerY;
            double distance = dx * dx + dy * dy;
            if (distance < best) {
                best = distance;
                selected = point;
            }
        }
        return selected;
    }

    private static void numberDuplicateRegionNames(Continent state) {
        Map<String, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < state.terrainRegions.size(); i++) {
            TerrainRegion region = state.terrainRegions.get(i);
            int occurrence = occurrences.getOrDefault(region.name, 0) + 1;
            occurrences.put(region.name, occurrence);
            if (occurrence > 1) state.terrainRegions.set(
                i,
                new TerrainRegion(
                    region.id,
                    region.name + " " + occurrence,
                    region.centerX,
                    region.centerY,
                    region.maximumBlockHeight,
                    region.cellCount,
                    region.noiseErosion));
        }
    }

    private static void computeTerrainPresetWeights(Continent state) {
        int size = state.terrainRegionIds.length;
        boolean[] mountain = new boolean[size];
        boolean[] base = new boolean[size];
        for (int cell = 0; cell < size; cell++) {
            int region = state.terrainRegionIds[cell];
            mountain[cell] = region > 0 && region <= state.terrainRegionKinds.size()
                && state.terrainRegionKinds.get(region - 1) == TerrainRegionKind.MOUNTAIN;
            base[cell] = !mountain[cell];
        }

        int radius = Math.max(1, state.config.terrainPresetTransitionRadius);
        int[] distanceToMountain = terrainPresetDistance(state, mountain, radius + 1);
        int[] distanceToBase = terrainPresetDistance(state, base, radius + 1);
        state.noiseErosionWeights = new double[size];
        for (int cell = 0; cell < size; cell++) {
            if (state.height[cell] <= state.config.seaLevel) continue;
            int distance = mountain[cell] ? distanceToBase[cell] : distanceToMountain[cell];
            if (distance == Integer.MAX_VALUE) {
                state.noiseErosionWeights[cell] = mountain[cell] ? 1.0 : 0.0;
                continue;
            }
            double normalized = Math.max(0.0, Math.min(1.0, (distance - 0.5) / radius));
            double fade = normalized * normalized * (3.0 - 2.0 * normalized);
            state.noiseErosionWeights[cell] = mountain[cell] ? 0.5 + 0.5 * fade : 0.5 - 0.5 * fade;
        }
    }

    private static int[] terrainPresetDistance(Continent state, boolean[] seeds, int limit) {
        int[] distance = new int[seeds.length];
        Arrays.fill(distance, Integer.MAX_VALUE);
        Queue<Integer> frontier = new ArrayDeque<>();
        for (int cell = 0; cell < seeds.length; cell++) {
            if (!seeds[cell]) continue;
            distance[cell] = 0;
            frontier.add(cell);
        }
        while (!frontier.isEmpty()) {
            int cell = frontier.remove();
            if (distance[cell] >= limit) continue;
            int next = distance[cell] + 1;
            for (int neighbor : state.index.terrainNeighbors[cell]) {
                if (neighbor != cell && state.height[neighbor] > state.config.seaLevel && next < distance[neighbor]) {
                    distance[neighbor] = next;
                    frontier.add(neighbor);
                }
            }
        }
        return distance;
    }

    private static void dilateMountainsToHillThreshold(Continent state) {
        boolean changed;
        do {
            changed = false;
            int[] claims = new int[state.terrainRegionIds.length];
            for (int cell = 0; cell < claims.length; cell++) {
                if (state.terrainRegionIds[cell] != 0
                    || state.height[cell] <= state.config.terrainRegionHillMinimumHeight) continue;
                int selected = 0;
                for (int neighbor : state.index.terrainNeighbors[cell]) {
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

    private static String relativeDirection(double dx, double dy, double centralRadius) {
        if (Math.hypot(dx, dy) < centralRadius) return "Central";
        String[] directions = { "Eastern", "North-Eastern", "Northern", "North-Western", "Western", "South-Western",
            "Southern", "South-Eastern" };
        int sector = Math.floorMod((int) Math.round(Math.atan2(dy, dx) / (Math.PI / 4.0)), directions.length);
        return directions[sector];
    }

    private static void writeTerrainRegionFields(Continent state) {
        for (int index = 0; index < state.index.coordinates.size(); index++) {
            Hex hex = state.grid.get(state.index.coordinates.get(index));
            hex.terrainRegionId = state.terrainRegionIds[index];
            hex.erosionWeight = state.noiseErosionWeights[index];
        }
    }

    /** Immutable terrain-region result consumed by world composition and preview decoration. */
    public static final class TerrainRegion {

        public final int id;
        public final String name;
        public final double centerX, centerY;
        public final double maximumBlockHeight;
        public final int cellCount;
        public final boolean noiseErosion;

        private TerrainRegion(int id, String name, double centerX, double centerY, double maximumBlockHeight,
            int cellCount, boolean noiseErosion) {
            this.id = id;
            this.name = name;
            this.centerX = centerX;
            this.centerY = centerY;
            this.maximumBlockHeight = maximumBlockHeight;
            this.cellCount = cellCount;
            this.noiseErosion = noiseErosion;
        }
    }

    public enum TerrainRegionKind {
        MOUNTAIN,
        HILL,
        PLAIN
    }

}
