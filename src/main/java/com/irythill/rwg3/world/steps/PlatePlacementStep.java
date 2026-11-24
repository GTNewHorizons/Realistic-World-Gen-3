package com.irythill.rwg3.world.steps;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.irythill.rwg3.util.hex.AxialCoordinate;
import com.irythill.rwg3.util.hex.GridIndex;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.World;
import com.irythill.rwg3.world.WorldGenerator.Plate;

/** Seeds, flood-fills, and smooths the toroidal tectonic plate field. */
public final class PlatePlacementStep implements IGenerationStep {

    private static final int PLATE_SIZE = 20;
    private static final int SMOOTHING_PASSES = 3;

    @Override
    public void apply(long seed, World world) {
        Random random = new Random(seed);
        List<LinkedHashSet<AxialCoordinate>> frontiers = seedPlates(world, random);
        floodFill(world, random, frontiers);
        smooth(world);
        recomputeCenters(world);
    }

    private static List<LinkedHashSet<AxialCoordinate>> seedPlates(World world, Random random) {
        List<LinkedHashSet<AxialCoordinate>> frontiers = new ArrayList<>();
        Set<AxialCoordinate> blockedSeeds = new HashSet<>();
        for (int x = 0; x < world.grid.width; x += PLATE_SIZE) {
            for (int y = 0; y < world.grid.height; y += PLATE_SIZE) {
                Vec2 seed = new Vec2(x + random.nextDouble() * PLATE_SIZE, y + random.nextDouble() * PLATE_SIZE);
                AxialCoordinate root = world.grid.wrap(AxialCoordinate.fromWorld(seed));
                if (blockedSeeds.contains(root) || world.grid.get(root).plateId != 0) continue;

                int plateId = world.plates.size() + 1;
                double angle = random.nextDouble() * Math.PI * 2.0;
                Vec2 drift = new Vec2(Math.cos(angle), Math.sin(angle)).multiply(World.PLATE_DRIFT);
                double spin = (random.nextDouble() * 2.0 - 1.0) * World.PLATE_SPIN;
                Plate plate = new Plate(drift, spin);
                plate.center = world.grid.wrap(seed);
                world.plates.add(plate);
                world.grid.get(root).plateId = plateId;

                LinkedHashSet<AxialCoordinate> frontier = new LinkedHashSet<>();
                for (int direction = 0; direction < 6; direction++) {
                    AxialCoordinate neighbor = world.grid.wrap(root.neighbor(direction));
                    blockedSeeds.add(neighbor);
                    if (world.grid.get(neighbor).plateId == 0) frontier.add(neighbor);
                }
                frontiers.add(frontier);
            }
        }
        return frontiers;
    }

    private static void floodFill(World world, Random random, List<LinkedHashSet<AxialCoordinate>> frontiers) {
        boolean progressed;
        do {
            progressed = false;
            for (int index = 0; index < frontiers.size(); index++) {
                LinkedHashSet<AxialCoordinate> frontier = frontiers.get(index);
                while (!frontier.isEmpty()) {
                    AxialCoordinate coordinate = removeAt(frontier, random.nextInt(frontier.size()));
                    if (world.grid.get(coordinate).plateId != 0) continue;

                    world.grid.get(coordinate).plateId = index + 1;
                    progressed = true;
                    for (int direction = 0; direction < 6; direction++) {
                        AxialCoordinate neighbor = world.grid.wrap(coordinate.neighbor(direction));
                        if (world.grid.get(neighbor).plateId == 0) frontier.add(neighbor);
                    }
                    break;
                }
            }
        } while (progressed);

        for (AxialCoordinate coordinate : world.grid.coordinates) {
            if (world.grid.get(coordinate).plateId == 0)
                throw new IllegalStateException("Plate flood fill left an unclaimed hex");
        }
    }

    private static AxialCoordinate removeAt(LinkedHashSet<AxialCoordinate> values, int selected) {
        Iterator<AxialCoordinate> iterator = values.iterator();
        for (int index = 0; index < selected; index++) iterator.next();
        AxialCoordinate value = iterator.next();
        iterator.remove();
        return value;
    }

    private static void smooth(World world) {
        GridIndex index = new GridIndex(world.grid);
        int[] ids = new int[index.coordinates.size()];
        for (int cell = 0; cell < ids.length; cell++) ids[cell] = world.grid.get(index.coordinates.get(cell)).plateId;

        for (int pass = 0; pass < SMOOTHING_PASSES; pass++) {
            int[] smoothed = ids.clone();
            for (int cell = 0; cell < ids.length; cell++) {
                int[] neighborIds = new int[6];
                int[] counts = new int[6];
                int kinds = 0;
                for (int neighbor : index.neighbors[cell]) {
                    int id = ids[neighbor];
                    int slot = 0;
                    while (slot < kinds && neighborIds[slot] != id) slot++;
                    if (slot == kinds) neighborIds[kinds++] = id;
                    counts[slot]++;
                }
                int bestId = ids[cell];
                int bestCount = 0;
                for (int slot = 0; slot < kinds; slot++) {
                    if (counts[slot] > bestCount || counts[slot] == bestCount && neighborIds[slot] < bestId) {
                        bestId = neighborIds[slot];
                        bestCount = counts[slot];
                    }
                }
                if (bestCount > 4 || pass == 0 && bestCount == 4) smoothed[cell] = bestId;
            }
            ids = smoothed;
        }

        for (int cell = 0; cell < ids.length; cell++) world.grid.get(index.coordinates.get(cell)).plateId = ids[cell];
    }

    private static void recomputeCenters(World world) {
        double[] sumX = new double[world.plates.size() + 1];
        double[] sumY = new double[world.plates.size() + 1];
        int[] count = new int[world.plates.size() + 1];
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            int id = world.grid.get(coordinate).plateId;
            Vec2 point = coordinate.toWorld();
            Vec2 reference = world.plates.get(id - 1).center;
            sumX[id] += reference.x + wrappedDelta(point.x - reference.x, world.grid.width);
            sumY[id] += reference.y + wrappedDelta(point.y - reference.y, world.grid.height);
            count[id]++;
        }
        for (int id = 1; id <= world.plates.size(); id++) {
            if (count[id] == 0) continue;
            world.plates.get(id - 1).center = world.grid.wrap(new Vec2(sumX[id] / count[id], sumY[id] / count[id]));
        }
    }

    private static double wrappedDelta(double delta, double period) {
        double result = (delta + period * 0.5) % period;
        if (result < 0.0) result += period;
        return result - period * 0.5;
    }
}
