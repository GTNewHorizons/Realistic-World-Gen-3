package com.irythill.rwg3.world.steps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.irythill.rwg3.util.hex.AxialCoordinate;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.World;
import com.irythill.rwg3.world.WorldGenerator.Plate;

/** Randomly combines neighboring plates without changing the land/ocean classification. */
public final class MergePlatesStep implements IGenerationStep {

    private static final long MERGE_SALT = 0x4D45524745504C54L;

    @Override
    public void apply(long seed, World world) {
        int oldCount = world.plates.size();
        List<Set<Integer>> adjacency = plateAdjacency(world, oldCount);
        int[] cellCounts = plateCellCounts(world, oldCount);
        boolean[] protectedSeeds = protectedSeeds(world, oldCount);
        int[] groupOf = createGroups(seed, world, adjacency, protectedSeeds);
        rebuildPlates(world, groupOf, cellCounts);
    }

    private static int[] createGroups(long seed, World world, List<Set<Integer>> adjacency, boolean[] protectedSeeds) {
        int[] groupOf = new int[world.plates.size() + 1];
        int groups = 0;
        List<Integer> order = new ArrayList<>();
        for (int id = 1; id <= world.plates.size(); id++) order.add(id);
        Random random = new Random(seed ^ MERGE_SALT);
        Collections.shuffle(order, random);

        for (int plate : order) {
            if (groupOf[plate] != 0) continue;
            List<Integer> candidates = candidates(world, plate, adjacency, groupOf, protectedSeeds);
            int group = ++groups;
            groupOf[plate] = group;
            if (candidates.isEmpty()) {
                continue;
            }
            int neighbor = candidates.get(random.nextInt(candidates.size()));
            groupOf[neighbor] = group;
        }
        return groupOf;
    }

    private static List<Integer> candidates(World world, int plate, List<Set<Integer>> adjacency, int[] groupOf,
        boolean[] protectedSeeds) {
        List<Integer> result = new ArrayList<>();
        boolean oceanic = world.plates.get(plate - 1).oceanic;
        for (int neighbor : adjacency.get(plate)) {
            if (world.plates.get(neighbor - 1).oceanic != oceanic) continue;
            if (groupOf[neighbor] != 0) continue;
            if (protectedSeeds[plate] && protectedSeeds[neighbor]) continue;
            result.add(neighbor);
        }
        Collections.sort(result);
        return result;
    }

    private static void rebuildPlates(World world, int[] groupOf, int[] cellCounts) {
        List<Plate> oldPlates = new ArrayList<>(world.plates);
        int groups = 0;
        for (int id = 1; id < groupOf.length; id++) groups = Math.max(groups, groupOf[id]);
        double[] driftX = new double[groups + 1];
        double[] driftY = new double[groups + 1];
        double[] spin = new double[groups + 1];
        int[] weight = new int[groups + 1];
        int[][] seedWeights = new int[groups + 1][ContinentalPlatesStep.BLOB_COUNT];
        boolean[] oceanic = new boolean[groups + 1];
        Vec2[] reference = new Vec2[groups + 1];

        for (int id = 1; id < groupOf.length; id++) {
            int group = groupOf[id];
            Plate plate = oldPlates.get(id - 1);
            int cells = cellCounts[id];
            driftX[group] += plate.drift.x * cells;
            driftY[group] += plate.drift.y * cells;
            spin[group] += plate.spin * cells;
            weight[group] += cells;
            oceanic[group] = plate.oceanic;
            if (plate.continentSeed >= 0) seedWeights[group][plate.continentSeed] += cells;
            if (reference[group] == null) reference[group] = plate.center;
        }

        world.plates.clear();
        for (int group = 1; group <= groups; group++) {
            Plate plate = new Plate(
                new Vec2(driftX[group] / weight[group], driftY[group] / weight[group]),
                spin[group] / weight[group]);
            plate.oceanic = oceanic[group];
            plate.continentSeed = maximumIndex(seedWeights[group]);
            plate.center = reference[group];
            world.plates.add(plate);
        }
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            int old = world.grid.get(coordinate).plateId;
            world.grid.get(coordinate).plateId = groupOf[old];
        }
        for (int blob = 0; blob < world.continentSeedPlateIds.length; blob++) {
            int group = groupOf[world.continentSeedPlateIds[blob]];
            world.continentSeedPlateIds[blob] = group;
            world.plates.get(group - 1).continentSeed = blob;
        }
        recomputeCenters(world);
        recomputeContinentCounts(world);
    }

    private static int maximumIndex(int[] values) {
        int selected = 0;
        for (int index = 1; index < values.length; index++) if (values[index] > values[selected]) selected = index;
        return selected;
    }

    private static void recomputeCenters(World world) {
        double[] sumX = new double[world.plates.size() + 1];
        double[] sumY = new double[world.plates.size() + 1];
        int[] count = new int[world.plates.size() + 1];
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            int id = world.grid.get(coordinate).plateId;
            Vec2 point = coordinate.toWorld();
            Vec2 center = world.plates.get(id - 1).center;
            sumX[id] += center.x + wrappedDelta(point.x - center.x, world.grid.width);
            sumY[id] += center.y + wrappedDelta(point.y - center.y, world.grid.height);
            count[id]++;
        }
        for (int id = 1; id <= world.plates.size(); id++)
            world.plates.get(id - 1).center = world.grid.wrap(new Vec2(sumX[id] / count[id], sumY[id] / count[id]));
    }

    private static void recomputeContinentCounts(World world) {
        for (int blob = 0; blob < world.continentPlateCounts.length; blob++) world.continentPlateCounts[blob] = 0;
        for (Plate plate : world.plates)
            if (!plate.oceanic && plate.continentSeed >= 0) world.continentPlateCounts[plate.continentSeed]++;
    }

    private static List<Set<Integer>> plateAdjacency(World world, int count) {
        List<Set<Integer>> result = new ArrayList<>(count + 1);
        for (int id = 0; id <= count; id++) result.add(new HashSet<>());
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            int plate = world.grid.get(coordinate).plateId;
            for (int direction = 0; direction < 6; direction++) {
                int neighbor = world.grid.getWrapped(coordinate.neighbor(direction)).plateId;
                if (neighbor != plate) result.get(plate)
                    .add(neighbor);
            }
        }
        return result;
    }

    private static int[] plateCellCounts(World world, int count) {
        int[] result = new int[count + 1];
        for (AxialCoordinate coordinate : world.grid.coordinates) result[world.grid.get(coordinate).plateId]++;
        return result;
    }

    private static boolean[] protectedSeeds(World world, int count) {
        boolean[] result = new boolean[count + 1];
        for (int plate : world.continentSeedPlateIds) result[plate] = true;
        return result;
    }

    private static double wrappedDelta(double delta, double period) {
        double result = (delta + period * 0.5) % period;
        if (result < 0.0) result += period;
        return result - period * 0.5;
    }
}
