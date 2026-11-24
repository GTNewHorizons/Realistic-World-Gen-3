package com.irythill.rwg3.world.steps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.irythill.rwg3.util.hex.AxialCoordinate;
import com.irythill.rwg3.util.hex.Vec2;
import com.irythill.rwg3.world.Hex;
import com.irythill.rwg3.world.World;

/** Classifies plates around sixteen continent seeds, then erodes their ocean-facing edges. */
public final class ContinentalPlatesStep implements IGenerationStep {

    public static final int GRID_SIZE = 4;
    public static final int BLOB_COUNT = GRID_SIZE * GRID_SIZE;
    private static final double CONTINENT_RADIUS = 36.0;
    private static final int INTERNAL_CHERRY_PICK = BLOB_COUNT;
    private static final int EXTERNAL_CHERRY_PICK = BLOB_COUNT * 2;
    private static final long SEED_SALT = 0x434F4E54424C4F42L;

    @Override
    public void apply(long seed, World world) {
        List<Vec2> seeds = placeSeeds(seed, world);
        classifyByProximity(world, seeds);
        boolean[] proximityOceanic = proximityOceanic(world);
        cherryPickOceanic(world, proximityOceanic, INTERNAL_CHERRY_PICK, false);
        cherryPickOceanic(world, proximityOceanic, EXTERNAL_CHERRY_PICK, true);
        publish(world);
    }

    private static List<Vec2> placeSeeds(long seed, World world) {
        Random random = new Random(seed ^ SEED_SALT);
        boolean[] claimed = new boolean[world.plates.size() + 1];
        List<Vec2> seeds = new ArrayList<>(BLOB_COUNT);
        double cellSize = world.grid.width / GRID_SIZE;
        for (int row = 0; row < GRID_SIZE; row++) {
            for (int column = 0; column < GRID_SIZE; column++) {
                int blob = row * GRID_SIZE + column;
                Vec2 target = new Vec2(
                    (column + random.nextDouble()) * cellSize,
                    (row + random.nextDouble()) * cellSize);
                int plate = nearestPlate(world, target, claimed);
                claimed[plate] = true;
                world.continentSeedPlateIds[blob] = plate;
                seeds.add(target);
            }
        }
        return seeds;
    }

    private static void classifyByProximity(World world, List<Vec2> seeds) {
        double radiusSquared = CONTINENT_RADIUS * CONTINENT_RADIUS;
        for (int id = 1; id <= world.plates.size(); id++) {
            Vec2 center = world.plates.get(id - 1).center;
            int nearest = 0;
            double best = Double.POSITIVE_INFINITY;
            for (int blob = 0; blob < seeds.size(); blob++) {
                double distance = toroidalDistanceSquared(world, center, seeds.get(blob));
                if (distance < best) {
                    best = distance;
                    nearest = blob;
                }
            }
            world.plates.get(id - 1).continentSeed = nearest;
            world.plates.get(id - 1).oceanic = best > radiusSquared;
        }
        for (int plate : world.continentSeedPlateIds) world.plates.get(plate - 1).oceanic = false;
    }

    /** Variant of the Rust cherry-pick: only plates already touching the ocean can be selected. */
    private static boolean[] proximityOceanic(World world) {
        boolean[] result = new boolean[world.plates.size() + 1];
        for (int id = 1; id <= world.plates.size(); id++) result[id] = world.plates.get(id - 1).oceanic;
        return result;
    }

    private static void cherryPickOceanic(World world, boolean[] proximityOceanic, int count, boolean reverse) {
        int[] contact = oceanContact(world, proximityOceanic);
        Set<Integer> protectedSeeds = new HashSet<>();
        for (int plate : world.continentSeedPlateIds) protectedSeeds.add(plate);
        List<Integer> candidates = new ArrayList<>();
        for (int id = 1; id <= world.plates.size(); id++) {
            if (!world.plates.get(id - 1).oceanic && contact[id] > 0 && !protectedSeeds.contains(id))
                candidates.add(id);
        }
        Comparator<Integer> order = Comparator.comparingInt((Integer id) -> contact[id])
            .thenComparingInt(id -> id);
        candidates.sort(reverse ? order.reversed() : order);
        for (int index = 0; index < Math.min(count, candidates.size()); index++)
            world.plates.get(candidates.get(index) - 1).oceanic = true;
    }

    private static int[] oceanContact(World world, boolean[] proximityOceanic) {
        int[] contact = new int[world.plates.size() + 1];
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            int plate = world.grid.get(coordinate).plateId;
            if (proximityOceanic[plate]) continue;
            for (int direction = 0; direction < 6; direction++) {
                int neighbor = world.grid.getWrapped(coordinate.neighbor(direction)).plateId;
                if (neighbor != plate && proximityOceanic[neighbor]) contact[plate]++;
            }
        }
        return contact;
    }

    private static void publish(World world) {
        for (int blob = 0; blob < BLOB_COUNT; blob++) world.continentPlateCounts[blob] = 0;
        for (int id = 1; id <= world.plates.size(); id++) {
            if (!world.plates.get(id - 1).oceanic) world.continentPlateCounts[world.plates.get(id - 1).continentSeed]++;
        }
        for (AxialCoordinate coordinate : world.grid.coordinates) {
            Hex hex = world.grid.get(coordinate);
            hex.oceanic = world.plates.get(hex.plateId - 1).oceanic;
        }
    }

    private static int nearestPlate(World world, Vec2 target, boolean[] claimed) {
        int selected = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int id = 1; id <= world.plates.size(); id++) {
            if (claimed[id]) continue;
            double distance = toroidalDistanceSquared(world, world.plates.get(id - 1).center, target);
            if (distance < best) {
                selected = id;
                best = distance;
            }
        }
        if (selected == 0) throw new IllegalStateException("Not enough plates for continent seeds");
        return selected;
    }

    private static double toroidalDistanceSquared(World world, Vec2 left, Vec2 right) {
        double dx = wrappedDelta(left.x - right.x, world.grid.width);
        double dy = wrappedDelta(left.y - right.y, world.grid.height);
        return dx * dx + dy * dy;
    }

    private static double wrappedDelta(double delta, double period) {
        double result = (delta + period * 0.5) % period;
        if (result < 0.0) result += period;
        return result - period * 0.5;
    }
}
